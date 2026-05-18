package com.eventfulcommerce.order.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scripting.support.ResourceScriptSource
import org.springframework.stereotype.Service
import java.util.*

private val logger = KotlinLogging.logger {}

@Service
class InventoryReservationService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {
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


    /*
        앞에 {product:$productId} 붙이는 이유는 각 상품별 3개 키가 모두 같은 노드에 저장되도록 하기 위함.
        각 상품은 서로 다른 노드에 분산되어 부하 분산 효과.
     */
    private fun stockKey(productId: String) = "{product:$productId}:stock"
    private fun holdCountKey(productId: String) = "{product:$productId}:holdCount"
    private fun holdKey(productId: String, reservationId: UUID) = "{product:$productId}:hold:$reservationId"

    fun reserve(productId: String, orderId: UUID, quantity: Int, ttlSeconds: Long): UUID? {
        val reservationId = UUID.randomUUID()
        val holdValue = objectMapper.writeValueAsString(mapOf("orderId" to orderId.toString()))

        val ok = redisTemplate.execute(
            reserveScript,
            listOf(stockKey(productId), holdKey(productId, reservationId), holdCountKey(productId)),
            holdValue,
            quantity.toString()
        ) ?: 0L

        val success = ok == 1L
        if (success) {
            logger.debug { "재고 예약 성공: productId=$productId, orderId=$orderId, quantity=$quantity, reservationId=$reservationId" }
        } else {
            logger.warn { "재고 예약 실패 - 재고 부족: productId=$productId, orderId=$orderId, quantity=$quantity" }
        }
        
        return if (success) reservationId else null
    }

    fun commit(productId: String, reservationId: UUID, quantity: Int) {
        redisTemplate.execute(commitScript, listOf(holdKey(productId, reservationId), holdCountKey(productId)), quantity.toString())
        logger.debug { "재고 확정: productId=$productId, quantity=$quantity, reservationId=$reservationId" }
    }

    fun release(productId: String, reservationId: UUID, quantity: Int) {
        redisTemplate.execute(releaseScript, listOf(stockKey(productId), holdKey(productId, reservationId), holdCountKey(productId)), quantity.toString())
        logger.debug { "재고 해제: productId=$productId, quantity=$quantity, reservationId=$reservationId" }
    }

    fun initializeStock(productId: String, initialStock: Int) {
        val stockK = stockKey(productId)
        val holdK = holdCountKey(productId)
        redisTemplate.opsForValue().set(stockK, initialStock.toString())
        redisTemplate.opsForValue().set(holdK, "0")
        logger.info { "Redis 재고 초기화: productId=$productId, stock=$initialStock" }
    }

    fun adjustStock(productId: String, delta: Int) {
        val stockK = stockKey(productId)
        redisTemplate.opsForValue().increment(stockK, delta.toLong())
        logger.info { "Redis 재고 조정: productId=$productId, delta=$delta" }
    }

    fun getStockSummary(productId: String) {
        val stock = redisTemplate.opsForValue().get(stockKey(productId))?.toLong() ?: 0L
        val holds = redisTemplate.opsForValue().get(holdCountKey(productId))?.toLong() ?: 0L
        logger.info { "재고 현황 - productId=$productId, 가용: $stock, 예약 중: $holds" }
    }

    fun getAvailableStock(productId: String): Long =
        redisTemplate.opsForValue().get(stockKey(productId))?.toLong() ?: 0L
}
