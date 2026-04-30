package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.domain.entity.User
import com.eventfulcommerce.user.exception.AccountLockedException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class AccountLockService(
    private val userService: UserService
) {
    
    companion object {
        private const val LOCK_DURATION_MINUTES = 30L
    }
    
    /**
     * 계정 잠금
     */
    @Transactional
    fun lockAccount(userId: UUID) {
        val user = userService.findById(userId)
        val unlockTime = Instant.now().plus(LOCK_DURATION_MINUTES, ChronoUnit.MINUTES)
        
        user.accountLockedUntil = unlockTime
        userService.save(user)
        
        logger.warn { "🔒 계정 잠금: userId=$userId, 해제시간=$unlockTime" }
    }
    
    /**
     * 계정 잠금 해제
     */
    @Transactional
    fun unlockAccount(userId: UUID) {
        val user = userService.findById(userId)
        user.accountLockedUntil = null
        userService.save(user)
        
        logger.info { "🔓 계정 잠금 해제: userId=$userId" }
    }
    
    /**
     * 계정 잠금 여부 확인
     */
    fun isLocked(user: User): Boolean {
        val lockedUntil = user.accountLockedUntil ?: return false
        
        // 잠금 시간이 지났으면 자동 해제
        if (Instant.now().isAfter(lockedUntil)) {
            unlockAccount(user.id)
            return false
        }
        
        return true
    }
    
    /**
     * 계정 잠금 확인 및 예외 발생
     */
    fun checkAndThrowIfLocked(user: User) {
        if (isLocked(user)) {
            val unlockTime = user.accountLockedUntil!!
            throw AccountLockedException(unlockTime.toString())
        }
    }
    
    /**
     * 남은 잠금 시간 조회 (초)
     */
    fun getRemainingLockTime(user: User): Long {
        val lockedUntil = user.accountLockedUntil ?: return 0L
        
        val now = Instant.now()
        if (now.isAfter(lockedUntil)) {
            return 0L
        }
        
        return ChronoUnit.SECONDS.between(now, lockedUntil)
    }
}
