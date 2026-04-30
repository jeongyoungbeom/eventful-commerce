package com.eventfulcommerce.user.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class RateLimitService(
    private val redisTemplate: RedisTemplate<String, String>
) {
    
    companion object {
        private const val RATE_LIMIT_PREFIX = "rate_limit:"
        private const val MAX_REQUESTS_PER_MINUTE = 100
        private const val WINDOW_SIZE_SECONDS = 60L
    }
    
    /**
     * Rate Limit 확인 (IP 기반)
     * @return true: 허용, false: 차단
     */
    fun checkRateLimit(ipAddress: String): Boolean {
        val key = RATE_LIMIT_PREFIX + ipAddress
        val currentCount = getCurrentCount(ipAddress)
        
        if (currentCount >= MAX_REQUESTS_PER_MINUTE) {
            logger.warn { "⚠️ Rate Limit 초과: IP=$ipAddress, 요청=${currentCount}회" }
            return false
        }
        
        // 요청 카운트 증가
        incrementCount(ipAddress)
        
        return true
    }
    
    /**
     * 현재 요청 카운트 조회
     */
    fun getCurrentCount(ipAddress: String): Int {
        val key = RATE_LIMIT_PREFIX + ipAddress
        val value = redisTemplate.opsForValue().get(key)
        return value?.toIntOrNull() ?: 0
    }
    
    /**
     * 요청 카운트 증가
     */
    private fun incrementCount(ipAddress: String) {
        val key = RATE_LIMIT_PREFIX + ipAddress
        val currentCount = getCurrentCount(ipAddress)
        
        if (currentCount == 0) {
            // 첫 요청이면 TTL 설정
            redisTemplate.opsForValue().set(
                key,
                "1",
                WINDOW_SIZE_SECONDS,
                TimeUnit.SECONDS
            )
        } else {
            // 카운트만 증가 (TTL 유지)
            redisTemplate.opsForValue().increment(key)
        }
        
        logger.debug { "Rate Limit 카운트: IP=$ipAddress, 현재=${currentCount + 1}회" }
    }
    
    /**
     * 남은 요청 가능 횟수
     */
    fun getRemainingRequests(ipAddress: String): Int {
        val currentCount = getCurrentCount(ipAddress)
        return (MAX_REQUESTS_PER_MINUTE - currentCount).coerceAtLeast(0)
    }
    
    /**
     * 윈도우 리셋까지 남은 시간 (초)
     */
    fun getResetTime(ipAddress: String): Long {
        val key = RATE_LIMIT_PREFIX + ipAddress
        val ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS)
        return if (ttl > 0) ttl else 0L
    }
}
