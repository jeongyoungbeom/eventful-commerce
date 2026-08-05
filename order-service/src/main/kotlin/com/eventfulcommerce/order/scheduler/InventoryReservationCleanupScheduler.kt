package com.eventfulcommerce.order.scheduler

import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.eventfulcommerce.order.repository.ProductReadModelRepository
import com.eventfulcommerce.order.service.InventoryReservationActionResult
import com.eventfulcommerce.order.service.InventoryReservationMetadata
import com.eventfulcommerce.order.service.InventoryReservationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
class InventoryReservationCleanupScheduler(
    private val ordersRepository: OrdersRepository,
    private val productReadModelRepository: ProductReadModelRepository,
    private val inventoryReservationService: InventoryReservationService,
    @Value("\${order.inventory.orphan-cleanup-batch-size:100}")
    private val batchSize: Long,
    @Value("\${order.inventory.orphan-cleanup-failure-backoff-seconds:60}")
    private val failureBackoffSeconds: Long
) {
    @Volatile
    private var productIndexBackfilled = false

    init {
        require(batchSize > 0) { "batchSize must be greater than zero" }
        require(failureBackoffSeconds > 0) { "failureBackoffSeconds must be greater than zero" }
    }

    /**
     * Redis 예약 뒤 DB 트랜잭션이 커밋되기 전에 프로세스가 종료된 경우를 복구한다.
     * DB에 살아 있는 예약 주문은 기존 OrderExpireScheduler가 처리하므로 여기서는 건드리지 않는다.
     */
    @Scheduled(fixedDelayString = "\${order.inventory.orphan-cleanup-fixed-delay-ms:10000}")
    fun cleanupExpiredReservations() {
        val now = Instant.now()
        val productIds = runCatching { reservationProductIdsWithBackfill() }
            .getOrElse { exception ->
                logger.error(exception) { "Failed to scan reservation product index" }
                return
            }
        productIds.forEach { productId ->
            cleanupProduct(productId, now)
        }
    }

    @Synchronized
    private fun reservationProductIdsWithBackfill(): List<UUID> {
        val indexedProductIds = inventoryReservationService.findReservationProductIds()
        if (productIndexBackfilled) return indexedProductIds

        val legacyProductIds = productReadModelRepository.findAllProductIds()
        inventoryReservationService.registerReservationProducts(legacyProductIds)
        productIndexBackfilled = true

        logger.info { "Inventory reservation product index backfilled: products=${legacyProductIds.size}" }
        return (indexedProductIds + legacyProductIds).distinct()
    }

    private fun cleanupProduct(productId: UUID, now: Instant) {
        val productIdValue = productId.toString()
        val reservationIds = runCatching {
            inventoryReservationService.findExpiredReservationIds(productIdValue, now, batchSize)
        }.getOrElse { exception ->
            logger.error(exception) { "Failed to scan expired inventory holds: productId=$productId" }
            return
        }

        reservationIds.forEach { reservationId ->
            runCatching { cleanupReservation(productIdValue, reservationId) }
                .onFailure { exception ->
                    logger.error(exception) {
                        "Failed to clean up inventory hold: productId=$productId, reservationId=$reservationId"
                    }
                    runCatching {
                        inventoryReservationService.rescheduleExpirationIndexEntry(
                            productIdValue,
                            reservationId,
                            now.plusSeconds(failureBackoffSeconds)
                        )
                    }.onFailure { rescheduleException ->
                        logger.error(rescheduleException) {
                            "Failed to back off poison inventory hold: " +
                                "productId=$productId, reservationId=$reservationId"
                        }
                    }
                }
        }
    }

    private fun cleanupReservation(productId: String, reservationId: UUID) {
        val metadata = inventoryReservationService.getReservationMetadata(productId, reservationId)
        if (metadata == null) {
            inventoryReservationService.removeExpirationIndexEntry(productId, reservationId)
            return
        }

        check(metadata.productId == productId) {
            "Reservation metadata productId mismatch: keyProductId=$productId, metadata=${metadata.productId}"
        }

        val order = ordersRepository.findById(metadata.orderId).orElse(null)
        if (order == null) {
            releaseOrphan(metadata)
            return
        }

        when (order.status) {
            OrdersStatus.ORDER_CONFIRMED -> requireSuccessful(
                inventoryReservationService.commit(productId, reservationId, metadata.quantity),
                "commit",
                metadata
            )

            OrdersStatus.ORDER_CANCELED,
            OrdersStatus.ORDER_EXPIRED,
            OrdersStatus.ORDER_FAILED -> requireSuccessful(
                inventoryReservationService.release(productId, reservationId, metadata.quantity),
                "release",
                metadata
            )

            OrdersStatus.ORDER_RESERVED,
            OrdersStatus.ORDER_PARTIALLY_CANCELED -> {
                // Remove it from the orphan queue so active orders cannot starve actual orphans.
                // OrderExpireScheduler owns DB-backed reservation expiration.
                inventoryReservationService.removeExpirationIndexEntry(productId, reservationId)
                logger.debug {
                    "Expired hold still belongs to a DB-backed order; order expiration flow will handle it: " +
                        "orderId=${order.id}, reservationId=$reservationId, status=${order.status}"
                }
            }

            OrdersStatus.ORDER -> error(
                "Unexpected persisted order status: orderId=${order.id}, status=${order.status}"
            )
        }
    }

    private fun releaseOrphan(metadata: InventoryReservationMetadata) {
        val result = inventoryReservationService.release(
            metadata.productId,
            metadata.reservationId,
            metadata.quantity
        )
        requireSuccessful(result, "release orphan", metadata)

        if (result == InventoryReservationActionResult.APPLIED) {
            logger.warn {
                "Released orphan inventory hold because no order exists in DB: " +
                    "orderId=${metadata.orderId}, productId=${metadata.productId}, " +
                    "reservationId=${metadata.reservationId}, quantity=${metadata.quantity}"
            }
        } else {
            logger.debug {
                "Orphan inventory hold was already released: reservationId=${metadata.reservationId}"
            }
        }
    }

    private fun requireSuccessful(
        result: InventoryReservationActionResult,
        action: String,
        metadata: InventoryReservationMetadata
    ) {
        check(result.successful) {
            "Inventory cleanup $action failed: orderId=${metadata.orderId}, " +
                "productId=${metadata.productId}, reservationId=${metadata.reservationId}, result=$result"
        }
    }
}
