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
        앞에 {inventory{ 붙이는 이유는 3개 키가 모두 같은 노드에 저장되도록 하기 위함.
     */
    private fun stockKey() = "{inventory}:stock:default"
    private fun holdCountKey() = "{inventory}:holdCount:default"
    private fun holdKey(reservationId: UUID) = "{inventory}:hold:$reservationId"

    fun reserve(orderId: UUID, ttlSeconds: Long): UUID? {
        val reservationId = UUID.randomUUID()
        val holdValue = objectMapper.writeValueAsString(mapOf("orderId" to orderId.toString()))

        val ok = redisTemplate.execute(
            reserveScript,
            listOf(stockKey(), holdKey(reservationId), holdCountKey()),
            ttlSeconds.toString(),
            holdValue
        ) ?: 0L

        val success = ok == 1L
        if (success) {
            logger.debug { "재고 예약 성공: orderId=$orderId, reservationId=$reservationId" }
        } else {
            logger.warn { "재고 예약 실패 - 재고 부족: orderId=$orderId" }
        }
        
        return if (success) reservationId else null
    }

    fun commit(reservationId: UUID) {
        redisTemplate.execute(commitScript, listOf(holdKey(reservationId), holdCountKey()))
        logger.debug { "재고 확정: reservationId=$reservationId" }
    }

    fun release(reservationId: UUID) {
        redisTemplate.execute(releaseScript, listOf(stockKey(), holdKey(reservationId), holdCountKey()))
        logger.debug { "재고 해제: reservationId=$reservationId" }
    }

    fun getStockSummary() {
        val stock = redisTemplate.opsForValue().get(stockKey())?.toLong() ?: 0L
        val holds = redisTemplate.opsForValue().get(holdCountKey())?.toLong() ?: 0L
        logger.info { "재고 현황 - 가용: $stock, 예약 중: $holds" }
    }
}