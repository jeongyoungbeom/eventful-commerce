package com.eventfulcommerce.user.service

import com.eventfulcommerce.common.auth.UserRole
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class RefreshTokenService(
    private val redisTemplate: RedisTemplate<String, String>
) {

    companion object {
        private const val USER_REFRESH_PREFIX = "refresh_token:user:"
        private const val SELLER_REFRESH_PREFIX = "refresh_token:seller:"
        private const val TTL_MS = 604800000L  // 7일
    }

    fun saveRefreshToken(id: UUID, refreshToken: String, role: UserRole) {
        if (role == UserRole.ADMIN) return
        redisTemplate.opsForValue().set(buildKey(id, role), refreshToken, TTL_MS, TimeUnit.MILLISECONDS)
        logger.info { "Refresh Token 저장: id=$id, role=$role" }
    }

    fun validateRefreshToken(id: UUID, refreshToken: String, role: UserRole): Boolean {
        if (role == UserRole.ADMIN) return true
        val stored = redisTemplate.opsForValue().get(buildKey(id, role))
        if (stored == null) {
            logger.warn { "Refresh Token 없음: id=$id, role=$role" }
            return false
        }
        val isValid = stored == refreshToken
        if (!isValid) logger.warn { "Refresh Token 불일치: id=$id, role=$role" }
        return isValid
    }

    fun deleteRefreshToken(id: UUID, role: UserRole) {
        if (role == UserRole.ADMIN) return
        redisTemplate.delete(buildKey(id, role))
        logger.info { "Refresh Token 삭제: id=$id, role=$role" }
    }

    fun getRemainingTTL(id: UUID, role: UserRole): Long? {
        if (role == UserRole.ADMIN) return null
        val ttl = redisTemplate.getExpire(buildKey(id, role), TimeUnit.SECONDS)
        return if (ttl > 0) ttl else null
    }

    private fun buildKey(id: UUID, role: UserRole): String = when (role) {
        UserRole.USER -> "$USER_REFRESH_PREFIX$id"
        UserRole.SELLER -> "$SELLER_REFRESH_PREFIX$id"
        UserRole.ADMIN -> throw IllegalArgumentException("ADMIN은 refresh token을 사용하지 않습니다.")
    }
}
