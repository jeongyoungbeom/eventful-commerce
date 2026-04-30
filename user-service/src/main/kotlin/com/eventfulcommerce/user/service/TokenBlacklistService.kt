package com.eventfulcommerce.user.service

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
    
    /**
     * 토큰이 Blacklist에 있는지 확인
     */
    fun isBlacklisted(accessToken: String): Boolean {
        val key = BLACKLIST_PREFIX + accessToken
        val exists = redisTemplate.hasKey(key)
        
        if (exists) {
            logger.debug { "🚫 Blacklist된 토큰 감지" }
        }
        
        return exists
    }
    
    /**
     * Blacklist에서 토큰 제거 (테스트/관리 용도)
     */
    fun removeFromBlacklist(accessToken: String) {
        val key = BLACKLIST_PREFIX + accessToken
        redisTemplate.delete(key)
        
        logger.info { "🗑️ Blacklist에서 제거" }
    }
    
    /**
     * 모든 Blacklist 토큰 삭제 (관리 용도)
     */
    fun clearAllBlacklisted(): Long {
        val pattern = "$BLACKLIST_PREFIX*"
        val keys = redisTemplate.keys(pattern)
        
        return if (keys.isNotEmpty()) {
            redisTemplate.delete(keys)
            logger.info { "🗑️ 전체 Blacklist 삭제: ${keys.size}개" }
            keys.size.toLong()
        } else {
            0L
        }
    }
}
