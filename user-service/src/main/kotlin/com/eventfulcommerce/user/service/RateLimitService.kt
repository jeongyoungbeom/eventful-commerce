package com.eventfulcommerce.user.service

import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class RateLimitService(
    private val redisTemplate: RedisTemplate<String, String>
) {
    
    companion object {
        private const val RATE_LIMIT_PREFIX = "rate_limit:"
        private const val MAX_REQUESTS_PER_MINUTE = 100
        private const val WINDOW_SIZE_SECONDS = 60L
        private val CHECK_LIMIT = DefaultRedisScript(
            """
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count >= tonumber(ARGV[1]) then
                if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
                return 0
            end
            redis.call('INCR', KEYS[1])
            if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            return 1
            """.trimIndent(), Long::class.javaObjectType
        )
    }
    
    /**
     * Rate Limit 확인 (IP 기반)
     * @return true: 허용, false: 차단
     */
    fun checkRateLimit(ipAddress: String): Boolean = checkNotNull(redisTemplate.execute(
        CHECK_LIMIT, listOf(RATE_LIMIT_PREFIX + ipAddress),
        MAX_REQUESTS_PER_MINUTE.toString(), WINDOW_SIZE_SECONDS.toString()
    )) { "요청 제한 카운터를 확인하지 못했습니다" } == 1L

    /**
     * 현재 요청 카운트 조회
     */
    fun getCurrentCount(ipAddress: String): Int {
        val key = RATE_LIMIT_PREFIX + ipAddress
        val value = redisTemplate.opsForValue().get(key)
        return value?.toIntOrNull() ?: 0
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
