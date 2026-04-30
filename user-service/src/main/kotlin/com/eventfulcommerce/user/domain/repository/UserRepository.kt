package com.eventfulcommerce.user.domain.repository

import com.eventfulcommerce.user.domain.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<User, UUID> {
    
    fun findByEmail(email: String): User?
    
    fun existsByEmail(email: String): Boolean
}
