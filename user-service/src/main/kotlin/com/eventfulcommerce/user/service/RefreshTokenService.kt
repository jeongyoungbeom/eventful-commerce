package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.config.JwtConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class RefreshTokenService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val jwtConfig: JwtConfig,
    private val jwtTokenProvider: JwtTokenProvider
) {
    
    companion object {
        private const val REFRESH_TOKEN_PREFIX = "refresh_token:"
    }
    
    /**
     * Refresh Token을 Redis에 저장
     */
    fun saveRefreshToken(userId: UUID, refreshToken: String) {
        val key = REFRESH_TOKEN_PREFIX + userId.toString()
        val ttl = jwtConfig.refreshTokenValidity
        
        redisTemplate.opsForValue().set(
            key,
            refreshToken,
            ttl,
            TimeUnit.MILLISECONDS
        )
        
        logger.info { "✅ Refresh Token 저장: userId=$userId, TTL=${ttl}ms" }
    }
    
    /**
     * Refresh Token 검증
     * Redis에 저장된 토큰과 일치하는지 확인
     */
    fun validateRefreshToken(userId: UUID, refreshToken: String): Boolean {
        val key = REFRESH_TOKEN_PREFIX + userId.toString()
        val storedToken = redisTemplate.opsForValue().get(key)
        
        if (storedToken == null) {
            logger.warn { "⚠️ Refresh Token 없음: userId=$userId" }
            return false
        }
        
        val isValid = storedToken == refreshToken
        
        if (!isValid) {
            logger.warn { "⚠️ Refresh Token 불일치: userId=$userId" }
        }
        
        return isValid
    }
    
    /**
     * Refresh Token 삭제 (로그아웃)
     */
    fun deleteRefreshToken(userId: UUID) {
        val key = REFRESH_TOKEN_PREFIX + userId.toString()
        redisTemplate.delete(key)
        
        logger.info { "🗑️ Refresh Token 삭제: userId=$userId" }
    }
    
    /**
     * Access Token 재발급
     */
    fun refreshAccessToken(refreshToken: String): String? {
        return try {
            // 1. Refresh Token 검증
            if (!jwtTokenProvider.validateToken(refreshToken)) {
                logger.warn { "⚠️ Refresh Token 검증 실패" }
                return null
            }
            
            // 2. 토큰 타입 확인
            val tokenType = jwtTokenProvider.getTokenType(refreshToken)
            if (tokenType != "refresh") {
                logger.warn { "⚠️ Access Token으로 재발급 시도" }
                return null
            }
            
            // 3. userId 추출
            val userId = jwtTokenProvider.getUserId(refreshToken)
            
            // 4. Redis에 저장된 토큰과 비교
            if (!validateRefreshToken(userId, refreshToken)) {
                logger.warn { "⚠️ Refresh Token이 Redis에 없거나 불일치" }
                return null
            }
            
            // 5. User 정보 조회 필요 (role을 알아야 함)
            // TODO: UserRepository에서 조회
            // 임시로 USER role로 발급 (나중에 수정 필요)
            logger.warn { "⚠️ 임시로 USER role로 Access Token 발급 (UserRepository 연동 필요)" }
            
            // 6. 새로운 Access Token 발급
            null  // TODO: UserRepository 연동 후 구현
            
        } catch (e: Exception) {
            logger.error(e) { "❌ Access Token 재발급 실패" }
            null
        }
    }
    
    /**
     * Refresh Token 남은 유효 시간 (초)
     */
    fun getRemainingTTL(userId: UUID): Long? {
        val key = REFRESH_TOKEN_PREFIX + userId.toString()
        val ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS)
        
        return if (ttl > 0) ttl else null
    }
}
