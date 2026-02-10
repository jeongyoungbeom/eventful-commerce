package com.eventfulcommerce.order.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scripting.support.ResourceScriptSource
import org.springframework.stereotype.Service
import java.util.*

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

    private fun stockKey() = "stock:default"
    private fun holdCountKey() = "holdCount:default"
    private fun holdKey(reservationId: UUID) = "hold:$reservationId"

    fun reserve(orderId: UUID, ttlSeconds: Long): UUID? {
        val reservationId = UUID.randomUUID()
        val holdValue = objectMapper.writeValueAsString(mapOf("orderId" to orderId.toString()))

        val ok = redisTemplate.execute(
            reserveScript,
            listOf(stockKey(), holdKey(reservationId), holdCountKey()),
            ttlSeconds.toString(),
            holdValue
        ) ?: 0L

        return if (ok == 1L) reservationId else null
    }

    fun commit(reservationId: UUID) {
        redisTemplate.execute(commitScript, listOf(holdKey(reservationId), holdCountKey()))
    }

    fun release(reservationId: UUID) {
        redisTemplate.execute(releaseScript, listOf(stockKey(), holdKey(reservationId), holdCountKey()))
    }

    fun getStockSummary() {
        val stock = redisTemplate.opsForValue().get(stockKey())?.toLong() ?: 0L
        val holds = redisTemplate.opsForValue().get(holdCountKey())?.toLong() ?: 0L
        println("available $stock holds: $holds")
    }
}