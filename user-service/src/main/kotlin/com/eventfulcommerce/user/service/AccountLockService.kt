package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.domain.entity.Lockable
import com.eventfulcommerce.user.domain.entity.Seller
import com.eventfulcommerce.user.domain.entity.User
import com.eventfulcommerce.user.domain.repository.SellerRepository
import com.eventfulcommerce.user.domain.repository.UserRepository
import com.eventfulcommerce.user.exception.AccountLockedException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

@Service
class AccountLockService(
    private val userRepository: UserRepository,
    private val sellerRepository: SellerRepository
) {

    companion object {
        private const val LOCK_DURATION_MINUTES = 30L
    }

    fun lock(lockable: Lockable) {
        val unlockTime = Instant.now().plus(LOCK_DURATION_MINUTES, ChronoUnit.MINUTES)
        lockable.lockAccount(unlockTime)
        save(lockable)
        logger.warn { "계정 잠금: id=${lockable.id}, 해제시간=$unlockTime" }
    }

    fun unlock(lockable: Lockable) {
        lockable.unlockAccount()
        save(lockable)
        logger.info { "계정 잠금 해제: id=${lockable.id}" }
    }

    fun isLocked(lockable: Lockable): Boolean {
        val lockedUntil = lockable.accountLockedUntil ?: return false
        if (Instant.now().isAfter(lockedUntil)) {
            unlock(lockable)
            return false
        }
        return true
    }

    fun checkAndThrowIfLocked(lockable: Lockable) {
        if (isLocked(lockable)) {
            throw AccountLockedException(lockable.accountLockedUntil.toString())
        }
    }

    fun getRemainingLockTime(lockable: Lockable): Long {
        val lockedUntil = lockable.accountLockedUntil ?: return 0L
        val now = Instant.now()
        if (now.isAfter(lockedUntil)) return 0L
        return ChronoUnit.SECONDS.between(now, lockedUntil)
    }

    private fun save(lockable: Lockable) {
        when (lockable) {
            is User -> userRepository.save(lockable)
            is Seller -> sellerRepository.save(lockable)
        }
    }
}
