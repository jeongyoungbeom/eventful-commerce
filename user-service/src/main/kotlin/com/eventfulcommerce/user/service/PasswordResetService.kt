package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.exception.InvalidTokenException
import com.eventfulcommerce.user.exception.UserNotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class PasswordResetService(
    private val userService: UserService,
    private val redisTemplate: RedisTemplate<String, String>,
    private val passwordEncoder: PasswordEncoder,
    private val auditLogService: AuditLogService
) {
    
    companion object {
        private const val RESET_TOKEN_PREFIX = "password_reset:"
        private const val TOKEN_VALIDITY_MINUTES = 30L
    }
    
    /**
     * 비밀번호 재설정 토큰 생성
     */
    fun createResetToken(email: String): String {
        // User 존재 확인
        val user = userService.findByEmail(email)
            ?: throw UserNotFoundException(email)
        
        // 토큰 생성 (UUID)
        val resetToken = UUID.randomUUID().toString()
        
        // Redis에 저장: token -> userId
        val key = RESET_TOKEN_PREFIX + resetToken
        redisTemplate.opsForValue().set(
            key,
            user.id.toString(),
            TOKEN_VALIDITY_MINUTES,
            TimeUnit.MINUTES
        )
        
        logger.info { "🔑 비밀번호 재설정 토큰 생성: email=$email, token=$resetToken" }
        
        // TODO: 이메일로 토큰 전송 (Email Service 연동 필요)
        // emailService.sendPasswordResetEmail(email, resetToken)
        
        return resetToken
    }
    
    /**
     * 비밀번호 재설정 토큰 검증
     */
    fun validateResetToken(token: String): UUID {
        val key = RESET_TOKEN_PREFIX + token
        val userIdStr = redisTemplate.opsForValue().get(key)
            ?: throw InvalidTokenException("유효하지 않거나 만료된 토큰입니다")
        
        return UUID.fromString(userIdStr)
    }
    
    /**
     * 비밀번호 재설정
     */
    @Transactional
    fun resetPassword(token: String, newPassword: String, httpRequest: HttpServletRequest?) {
        // 토큰 검증
        val userId = validateResetToken(token)
        
        // User 조회
        val user = userService.findById(userId)
        
        // 비밀번호 변경
        user.password = passwordEncoder.encode(newPassword)
        userService.save(user)
        
        // 토큰 삭제 (재사용 방지)
        val key = RESET_TOKEN_PREFIX + token
        redisTemplate.delete(key)
        
        // 감사 로그
        auditLogService.logPasswordChange(userId, httpRequest)
        
        logger.info { "✅ 비밀번호 재설정 완료: userId=$userId" }
    }
    
    /**
     * 비밀번호 재설정 토큰 남은 시간 (초)
     */
    fun getRemainingTime(token: String): Long {
        val key = RESET_TOKEN_PREFIX + token
        val ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS)
        return if (ttl > 0) ttl else 0L
    }
}
