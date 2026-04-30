package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.exception.InvalidTokenException
import com.eventfulcommerce.user.exception.UserNotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class EmailVerificationService(
    private val userService: UserService,
    private val redisTemplate: RedisTemplate<String, String>
) {
    
    companion object {
        private const val VERIFICATION_TOKEN_PREFIX = "email_verification:"
        private const val TOKEN_VALIDITY_HOURS = 24L
    }
    
    /**
     * 이메일 인증 토큰 생성
     */
    fun createVerificationToken(userId: UUID): String {
        // 토큰 생성 (UUID)
        val verificationToken = UUID.randomUUID().toString()
        
        // Redis에 저장: token -> userId
        val key = VERIFICATION_TOKEN_PREFIX + verificationToken
        redisTemplate.opsForValue().set(
            key,
            userId.toString(),
            TOKEN_VALIDITY_HOURS,
            TimeUnit.HOURS
        )
        
        logger.info { "📧 이메일 인증 토큰 생성: userId=$userId, token=$verificationToken" }
        
        // TODO: 이메일로 토큰 전송 (Email Service 연동 필요)
        // emailService.sendVerificationEmail(user.email, verificationToken)
        
        return verificationToken
    }
    
    /**
     * 이메일 인증 처리
     */
    @Transactional
    fun verifyEmail(token: String) {
        // 토큰 검증
        val key = VERIFICATION_TOKEN_PREFIX + token
        val userIdStr = redisTemplate.opsForValue().get(key)
            ?: throw InvalidTokenException("유효하지 않거나 만료된 인증 토큰입니다")
        
        val userId = UUID.fromString(userIdStr)
        
        // User 조회
        val user = userService.findById(userId)
        
        // 이미 인증된 경우
        if (user.emailVerified) {
            logger.info { "이미 인증된 이메일: userId=$userId" }
            return
        }
        
        // 이메일 인증 처리
        user.emailVerified = true
        userService.save(user)
        
        // 토큰 삭제 (재사용 방지)
        redisTemplate.delete(key)
        
        logger.info { "✅ 이메일 인증 완료: userId=$userId" }
    }
    
    /**
     * 인증 토큰 재전송
     */
    fun resendVerificationToken(email: String): String {
        // User 조회
        val user = userService.findByEmail(email)
            ?: throw UserNotFoundException(email)
        
        // 이미 인증된 경우
        if (user.emailVerified) {
            throw IllegalStateException("이미 인증된 이메일입니다")
        }
        
        // 새로운 토큰 생성
        return createVerificationToken(user.id)
    }
    
    /**
     * 인증 토큰 남은 시간 (초)
     */
    fun getRemainingTime(token: String): Long {
        val key = VERIFICATION_TOKEN_PREFIX + token
        val ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS)
        return if (ttl > 0) ttl else 0L
    }
}
