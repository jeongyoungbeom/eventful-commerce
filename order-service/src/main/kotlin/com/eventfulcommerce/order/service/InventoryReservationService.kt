package com.eventfulcommerce.order.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scripting.support.ResourceScriptSource
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

enum class InventoryReservationState {
    RESERVED,
    COMMITTED,
    RELEASED,
    RESTOCKED
}

enum class InventoryReservationActionResult {
    APPLIED,
    ALREADY_APPLIED,
    NOT_FOUND,
    TERMINAL_CONFLICT,
    CORRUPTED;

    val successful: Boolean
        get() = this == APPLIED || this == ALREADY_APPLIED
}

class InventoryReservationTerminalStateException(
    reservationId: UUID,
    state: InventoryReservationState
) : IllegalStateException("Reservation is already in terminal state: reservationId=$reservationId, state=$state")

data class InventoryReservationMetadata(
    val orderId: UUID,
    val productId: String,
    val reservationId: UUID,
    val quantity: Int,
    val expiresAt: Instant,
    val state: InventoryReservationState = InventoryReservationState.RESERVED
)

data class InventoryStockSnapshot(
    val availableStock: Long?,
    val heldQuantity: Long
)

@Service
class InventoryReservationService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${order.inventory.terminal-retention-seconds:604800}")
    terminalRetentionSeconds: Long,
    @Value("\${order.inventory.stock-event-retention-seconds:2592000}")
    stockEventRetentionSeconds: Long = 2592000
) {
    private val terminalRetentionMillis: Long = Math.multiplyExact(
        terminalRetentionSeconds.also {
            require(it > 0) { "terminalRetentionSeconds must be greater than zero" }
        },
        1_000L
    )
    private val stockEventRetentionMillis: Long = Math.multiplyExact(
        stockEventRetentionSeconds.also {
            require(it > 0) { "stockEventRetentionSeconds must be greater than zero" }
        },
        1_000L
    )

    private val reserveScript = DefaultRedisScript<Long>().apply {
        resultType = Long::class.java
        setScriptSource(ResourceScriptSource(ClassPathResource("lua/reserve.lua")))
    }
    private val commitScript = DefaultRedisScript<Long>().apply {
        resultType = Long::class.java
        setScriptSource(ResourceScriptSource(ClassPathResource("lua/commit.lua")))
    }
    private val releaseScript = DefaultRedisScript<Long>().apply {
        resultType = Long::class.java
        setScriptSource(ResourceScriptSource(ClassPathResource("lua/release.lua")))
    }
    private val restockScript = DefaultRedisScript<Long>().apply {
        resultType = Long::class.java
        setScriptSource(ResourceScriptSource(ClassPathResource("lua/restock.lua")))
    }
    private val adjustStockIdempotentScript = DefaultRedisScript<Long>().apply {
        resultType = Long::class.java
        setScriptSource(ResourceScriptSource(ClassPathResource("lua/adjust-stock-idempotent.lua")))
    }

    /*
     * Redis Cluster에서 한 Lua 스크립트가 접근하는 모든 키는 같은 슬롯이어야 한다.
     * 따라서 상품별 키에는 동일한 {product:<id>} hash tag를 사용한다.
     */
    private fun stockKey(productId: String) = "{product:$productId}:stock"
    private fun holdCountKey(productId: String) = "{product:$productId}:holdCount"
    private fun holdKey(productId: String, reservationId: UUID) = "{product:$productId}:hold:$reservationId"
    private fun expirationKey(productId: String) = "{product:$productId}:holdExpirations"
    private fun stockEventKey(productId: String, eventId: UUID) = "{product:$productId}:stock-event:$eventId"
    private fun reservationProductsKey() = "inventory:reservation-products"

    /**
     * reservationId를 호출자가 재사용하면 같은 예약 요청을 여러 번 실행해도 재고는 한 번만 차감된다.
     * COMMITTED/RELEASED tombstone이 보존되는 동안 종료된 예약도 다시 생성되지 않는다.
     */
    fun reserve(
        productId: String,
        orderId: UUID,
        quantity: Int,
        ttlSeconds: Long,
        reservationId: UUID = UUID.randomUUID()
    ): UUID? {
        require(quantity > 0) { "quantity must be greater than zero" }
        require(ttlSeconds > 0) { "ttlSeconds must be greater than zero" }

        val expiresAt = Instant.now().plusSeconds(ttlSeconds)
        // Register first. A crash can leave a harmless stale product ID, but never an undiscoverable hold.
        redisTemplate.opsForSet().add(reservationProductsKey(), productId)
        val result = redisTemplate.execute(
            reserveScript,
            listOf(
                stockKey(productId),
                holdKey(productId, reservationId),
                holdCountKey(productId),
                expirationKey(productId)
            ),
            orderId.toString(),
            productId,
            reservationId.toString(),
            quantity.toString(),
            expiresAt.toEpochMilli().toString()
        )

        return when (result) {
            RESERVATION_CREATED -> {
                registerRollbackRelease(productId, reservationId, quantity)
                logger.debug {
                    "Inventory reserved: productId=$productId, orderId=$orderId, " +
                        "quantity=$quantity, reservationId=$reservationId, expiresAt=$expiresAt"
                }
                reservationId
            }

            RESERVATION_ALREADY_EXISTS -> {
                logger.debug {
                    "Inventory reservation already exists: productId=$productId, " +
                        "orderId=$orderId, reservationId=$reservationId"
                }
                reservationId
            }

            INSUFFICIENT_STOCK -> {
                logger.warn {
                    "Inventory reservation failed due to insufficient stock: " +
                        "productId=$productId, orderId=$orderId, quantity=$quantity"
                }
                null
            }

            RESERVATION_ALREADY_COMMITTED -> throw InventoryReservationTerminalStateException(
                reservationId,
                InventoryReservationState.COMMITTED
            )

            RESERVATION_ALREADY_RELEASED -> throw InventoryReservationTerminalStateException(
                reservationId,
                InventoryReservationState.RELEASED
            )

            RESERVATION_ALREADY_RESTOCKED -> throw InventoryReservationTerminalStateException(
                reservationId,
                InventoryReservationState.RESTOCKED
            )

            RESERVATION_CONFLICT -> throw IllegalStateException(
                "reservationId is already used with different reservation data: " +
                    "productId=$productId, reservationId=$reservationId"
            )

            RESERVATION_CORRUPTED -> throw IllegalStateException(
                "Reservation Redis data is corrupted: productId=$productId, reservationId=$reservationId"
            )

            INVENTORY_NOT_INITIALIZED -> throw IllegalStateException(
                "Inventory Redis data is missing or corrupted: productId=$productId"
            )

            else -> throw IllegalStateException(
                "Unexpected inventory reservation result: result=$result, " +
                    "productId=$productId, reservationId=$reservationId"
            )
        }
    }

    fun commit(
        productId: String,
        reservationId: UUID,
        quantity: Int
    ): InventoryReservationActionResult {
        val resultCode = redisTemplate.execute(
            commitScript,
            listOf(
                holdKey(productId, reservationId),
                holdCountKey(productId),
                expirationKey(productId)
            ),
            quantity.toString(),
            reservationId.toString(),
            terminalRetentionMillis.toString(),
            productId,
            Instant.now().toEpochMilli().toString()
        )
        val result = actionResult(resultCode)

        logActionResult("commit", productId, reservationId, quantity, result)
        return result
    }

    fun release(
        productId: String,
        reservationId: UUID,
        quantity: Int
    ): InventoryReservationActionResult {
        val resultCode = redisTemplate.execute(
            releaseScript,
            listOf(
                stockKey(productId),
                holdKey(productId, reservationId),
                holdCountKey(productId),
                expirationKey(productId)
            ),
            quantity.toString(),
            reservationId.toString(),
            terminalRetentionMillis.toString(),
            productId,
            Instant.now().toEpochMilli().toString()
        )
        val result = actionResult(resultCode)

        logActionResult("release", productId, reservationId, quantity, result)
        return result
    }

    /** 확정된 판매 재고를 취소로 복원한다. 같은 reservationId는 최초 한 번만 증가한다. */
    fun restock(
        productId: String,
        reservationId: UUID,
        quantity: Int
    ): InventoryReservationActionResult {
        val resultCode = redisTemplate.execute(
            restockScript,
            listOf(
                stockKey(productId),
                holdKey(productId, reservationId),
                expirationKey(productId)
            ),
            quantity.toString(),
            reservationId.toString(),
            terminalRetentionMillis.toString(),
            productId,
            Instant.now().toEpochMilli().toString()
        )
        val result = actionResult(resultCode)

        logActionResult("restock", productId, reservationId, quantity, result)
        return result
    }

    fun findExpiredReservationIds(productId: String, now: Instant, limit: Long): List<UUID> {
        require(limit > 0) { "limit must be greater than zero" }

        return redisTemplate.opsForZSet()
            .rangeByScore(expirationKey(productId), 0.0, now.toEpochMilli().toDouble(), 0, limit)
            .orEmpty()
            .mapNotNull { value ->
                runCatching { UUID.fromString(value) }
                    .onFailure {
                        logger.error { "Invalid reservationId in expiration index: productId=$productId, value=$value" }
                        redisTemplate.opsForZSet().remove(expirationKey(productId), value)
                    }
                    .getOrNull()
            }
    }

    fun findReservationProductIds(): List<UUID> = redisTemplate.opsForSet()
        .members(reservationProductsKey())
        .orEmpty()
        .mapNotNull { value ->
            runCatching { UUID.fromString(value) }
                .onFailure {
                    logger.error { "Invalid productId in reservation product index: value=$value" }
                    redisTemplate.opsForSet().remove(reservationProductsKey(), value)
                }
                .getOrNull()
        }

    fun registerReservationProducts(productIds: Collection<UUID>) {
        if (productIds.isEmpty()) return
        redisTemplate.opsForSet().add(
            reservationProductsKey(),
            *productIds.map(UUID::toString).toTypedArray()
        )
    }

    fun getReservationMetadata(productId: String, reservationId: UUID): InventoryReservationMetadata? {
        val values = redisTemplate.opsForHash<String, String>()
            .entries(holdKey(productId, reservationId))
        if (values.isEmpty()) return null

        fun required(name: String): String = values[name]
            ?: throw IllegalStateException(
                "Reservation metadata is missing '$name': productId=$productId, reservationId=$reservationId"
            )

        val storedReservationId = UUID.fromString(required("reservationId"))
        check(storedReservationId == reservationId) {
            "Reservation metadata has a mismatched reservationId: expected=$reservationId, actual=$storedReservationId"
        }

        return InventoryReservationMetadata(
            orderId = UUID.fromString(required("orderId")),
            productId = required("productId"),
            reservationId = storedReservationId,
            quantity = required("quantity").toInt(),
            expiresAt = Instant.ofEpochMilli(required("expiresAtEpochMilli").toLong()),
            state = values["status"]
                ?.let(InventoryReservationState::valueOf)
                ?: InventoryReservationState.RESERVED
        )
    }

    fun removeExpirationIndexEntry(productId: String, reservationId: UUID) {
        redisTemplate.opsForZSet().remove(expirationKey(productId), reservationId.toString())
    }

    fun rescheduleExpirationIndexEntry(productId: String, reservationId: UUID, retryAt: Instant) {
        redisTemplate.opsForZSet().add(
            expirationKey(productId),
            reservationId.toString(),
            retryAt.toEpochMilli().toDouble()
        )
    }

    fun initializeStock(productId: String, initialStock: Int) {
        require(initialStock >= 0) { "initialStock must not be negative" }
        redisTemplate.opsForValue().set(stockKey(productId), initialStock.toString())
        redisTemplate.opsForValue().set(holdCountKey(productId), "0")
        logger.info { "Redis inventory initialized: productId=$productId, stock=$initialStock" }
    }

    fun adjustStockIdempotent(
        productId: String,
        eventId: UUID,
        delta: Int
    ): InventoryReservationActionResult {
        val resultCode = redisTemplate.execute(
            adjustStockIdempotentScript,
            listOf(stockKey(productId), stockEventKey(productId, eventId)),
            delta.toString(),
            stockEventRetentionMillis.toString()
        )
        val result = actionResult(resultCode)

        if (result.successful) {
            logger.info {
                "Redis inventory event applied: productId=$productId, eventId=$eventId, delta=$delta, result=$result"
            }
        } else {
            logger.error {
                "Redis inventory event failed: productId=$productId, eventId=$eventId, delta=$delta, result=$result"
            }
        }
        return result
    }

    fun getStockSnapshot(productId: String): InventoryStockSnapshot = InventoryStockSnapshot(
        availableStock = redisTemplate.opsForValue().get(stockKey(productId))?.toLongOrNull(),
        heldQuantity = redisTemplate.opsForValue().get(holdCountKey(productId))?.toLongOrNull() ?: 0L
    )

    fun getAvailableStock(productId: String): Long =
        redisTemplate.opsForValue().get(stockKey(productId))?.toLong() ?: 0L

    private fun actionResult(resultCode: Long): InventoryReservationActionResult = when (resultCode) {
        ACTION_APPLIED -> InventoryReservationActionResult.APPLIED
        ACTION_ALREADY_APPLIED -> InventoryReservationActionResult.ALREADY_APPLIED
        ACTION_NOT_FOUND -> InventoryReservationActionResult.NOT_FOUND
        ACTION_TERMINAL_CONFLICT -> InventoryReservationActionResult.TERMINAL_CONFLICT
        ACTION_CORRUPTED -> InventoryReservationActionResult.CORRUPTED
        else -> throw IllegalStateException("Unexpected inventory action result: result=$resultCode")
    }

    private fun logActionResult(
        action: String,
        productId: String,
        reservationId: UUID,
        quantity: Int,
        result: InventoryReservationActionResult
    ) {
        val message = {
            "Inventory reservation $action: productId=$productId, quantity=$quantity, " +
                "reservationId=$reservationId, result=$result"
        }
        if (result.successful) {
            logger.debug(message)
        } else {
            logger.error(message)
        }
    }

    private fun registerRollbackRelease(productId: String, reservationId: UUID, quantity: Int) {
        if (!TransactionSynchronizationManager.isSynchronizationActive() ||
            !TransactionSynchronizationManager.isActualTransactionActive()
        ) {
            return
        }

        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) return

                runCatching { release(productId, reservationId, quantity) }
                    .onSuccess { result ->
                        if (!result.successful) {
                            logger.error {
                                "Inventory rollback compensation was not applied; expiration cleanup will retry: " +
                                    "productId=$productId, reservationId=$reservationId, result=$result"
                            }
                        }
                    }
                    .onFailure { exception ->
                        logger.error(exception) {
                            "Failed to release inventory after DB rollback; expiration cleanup will retry: " +
                                "productId=$productId, reservationId=$reservationId"
                        }
                    }
            }
        })
    }

    private companion object {
        const val INSUFFICIENT_STOCK = 0L
        const val RESERVATION_CREATED = 1L
        const val RESERVATION_ALREADY_EXISTS = 2L
        const val RESERVATION_ALREADY_COMMITTED = 3L
        const val RESERVATION_ALREADY_RELEASED = 4L
        const val RESERVATION_ALREADY_RESTOCKED = 5L
        const val RESERVATION_CONFLICT = -1L
        const val RESERVATION_CORRUPTED = -2L
        const val INVENTORY_NOT_INITIALIZED = -3L

        const val ACTION_NOT_FOUND = 0L
        const val ACTION_APPLIED = 1L
        const val ACTION_ALREADY_APPLIED = 2L
        const val ACTION_TERMINAL_CONFLICT = 3L
        const val ACTION_CORRUPTED = -1L
    }
}
