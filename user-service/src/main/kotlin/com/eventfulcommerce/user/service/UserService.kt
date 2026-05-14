package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.domain.entity.User
import com.eventfulcommerce.user.domain.repository.UserRepository
import com.eventfulcommerce.user.exception.UserNotFoundException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class UserService(
    private val userRepository: UserRepository
) {
    
    /**
     * User 조회 (ID로)
     */
    fun findById(userId: UUID): User {
        return userRepository.findById(userId)
            .orElseThrow { UserNotFoundException(userId.toString()) }
    }
    
    /**
     * User 조회 (이메일로)
     */
    fun findByEmail(email: String): User? {
        return userRepository.findByEmail(email)
    }
    
    /**
     * 이메일 중복 확인
     */
    fun existsByEmail(email: String): Boolean {
        return userRepository.existsByEmail(email)
    }
    
    /**
     * User 저장
     */
    @Transactional
    fun save(user: User): User {
        return userRepository.save(user)
    }
    
    /**
     * User 삭제
     */
    @Transactional
    fun delete(userId: UUID) {
        val user = findById(userId)
        userRepository.delete(user)
        logger.info { "🗑️ User 삭제: userId=$userId" }
    }
}
