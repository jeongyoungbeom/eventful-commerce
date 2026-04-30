package com.eventfulcommerce.user.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class LoginAttemptService(
    private val redisTemplate: RedisTemplate<String, String>
) {
    
    companion object {
        private const val LOGIN_ATTEMPT_PREFIX = "login_attempt:"
        private const val MAX_ATTEMPTS = 5
        private const val LOCK_DURATION_MINUTES = 30L
    }
    
    /**
     * 로그인 실패 기록 (IP 기반)
     */
    fun recordFailure(ipAddress: String) {
        val key = LOGIN_ATTEMPT_PREFIX + ipAddress
        val attempts = getAttempts(ipAddress) + 1
        
        redisTemplate.opsForValue().set(
            key,
            attempts.toString(),
            LOCK_DURATION_MINUTES,
            TimeUnit.MINUTES
        )
        
        logger.warn { "로그인 실패 기록: IP=$ipAddress, 시도=${attempts}회" }
    }
    
    /**
     * 로그인 실패 기록 (이메일 기반)
     */
    fun recordFailureByEmail(email: String) {
        val key = "${LOGIN_ATTEMPT_PREFIX}email:$email"
        val attempts = getAttemptsByEmail(email) + 1
        
        redisTemplate.opsForValue().set(
            key,
            attempts.toString(),
            LOCK_DURATION_MINUTES,
            TimeUnit.MINUTES
        )
        
        logger.warn { "로그인 실패 기록: email=$email, 시도=${attempts}회" }
    }
    
    /**
     * 로그인 성공 시 실패 기록 초기화
     */
    fun resetAttempts(ipAddress: String, email: String) {
        val ipKey = LOGIN_ATTEMPT_PREFIX + ipAddress
        val emailKey = "${LOGIN_ATTEMPT_PREFIX}email:$email"
        
        redisTemplate.delete(ipKey)
        redisTemplate.delete(emailKey)
        
        logger.info { "로그인 성공: 실패 기록 초기화 - IP=$ipAddress, email=$email" }
    }
    
    /**
     * IP 기반 로그인 시도 횟수 조회
     */
    fun getAttempts(ipAddress: String): Int {
        val key = LOGIN_ATTEMPT_PREFIX + ipAddress
        val value = redisTemplate.opsForValue().get(key)
        return value?.toIntOrNull() ?: 0
    }
    
    /**
     * 이메일 기반 로그인 시도 횟수 조회
     */
    fun getAttemptsByEmail(email: String): Int {
        val key = "${LOGIN_ATTEMPT_PREFIX}email:$email"
        val value = redisTemplate.opsForValue().get(key)
        return value?.toIntOrNull() ?: 0
    }
    
    /**
     * IP 기반 로그인 차단 여부 확인
     */
    fun isBlocked(ipAddress: String): Boolean {
        val attempts = getAttempts(ipAddress)
        val blocked = attempts >= MAX_ATTEMPTS
        
        if (blocked) {
            logger.warn { "IP 차단됨: $ipAddress (시도 ${attempts}회)" }
        }
        
        return blocked
    }
    
    /**
     * 이메일 기반 로그인 차단 여부 확인
     */
    fun isBlockedByEmail(email: String): Boolean {
        val attempts = getAttemptsByEmail(email)
        val blocked = attempts >= MAX_ATTEMPTS
        
        if (blocked) {
            logger.warn { "이메일 차단됨: $email (시도 ${attempts}회)" }
        }
        
        return blocked
    }
    
    /**
     * 남은 잠금 시간 조회 (초)
     */
    fun getRemainingLockTime(ipAddress: String): Long {
        val key = LOGIN_ATTEMPT_PREFIX + ipAddress
        val ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS)
        return if (ttl > 0) ttl else 0L
    }
}
