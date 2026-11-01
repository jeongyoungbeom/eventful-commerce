package com.eventfulcommerce.user.domain.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import com.eventfulcommerce.user.domain.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<User, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from User account where account.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): User?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from User account where account.email = :email")
    fun findByEmailForUpdate(@Param("email") email: String): User?

    
    fun findByEmail(email: String): User?
    
    fun existsByEmail(email: String): Boolean
}
