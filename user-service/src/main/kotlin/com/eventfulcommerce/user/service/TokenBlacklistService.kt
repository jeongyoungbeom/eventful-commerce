package com.eventfulcommerce.user.service

import com.eventfulcommerce.common.auth.JwtTokenProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class TokenBlacklistService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val jwtTokenProvider: JwtTokenProvider
) {
    
    companion object {
        private const val BLACKLIST_PREFIX = "blacklist:"
    }
    
    /**
     * Access Token을 Blacklist에 추가 (로그아웃)
     */
    fun addToBlacklist(accessToken: String) {
        try {
            val key = BLACKLIST_PREFIX + accessToken
            val remainingValidity = jwtTokenProvider.getRemainingValidity(accessToken)
            
            if (remainingValidity > 0) {
                redisTemplate.opsForValue().set(
                    key,
                    "logged_out",
                    remainingValidity,
                    TimeUnit.MILLISECONDS
                )
                
                logger.info { "🚫 Access Token Blacklist 추가: TTL=${remainingValidity}ms" }
            } else {
                logger.warn { "⚠️ 이미 만료된 토큰: Blacklist 추가 불필요" }
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Blacklist 추가 실패" }
        }
    }
}
