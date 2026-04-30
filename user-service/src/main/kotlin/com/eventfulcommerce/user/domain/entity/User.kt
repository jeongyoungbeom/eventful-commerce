package com.eventfulcommerce.user.domain.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    
    @Column(unique = true, nullable = false, length = 255)
    val email: String,
    
    @Column(nullable = false, length = 255)
    var password: String,
    
    @Column(nullable = false, length = 100)
    var name: String,
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val role: UserRole = UserRole.USER,
    
    @Column(nullable = false)
    var emailVerified: Boolean = false,
    
    @Column
    var emailVerificationToken: String? = null,
    
    @Column
    var accountLockedUntil: Instant? = null,
    
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    fun isAccountLocked(): Boolean {
        return accountLockedUntil?.let { it.isAfter(Instant.now()) } ?: false
    }
    
    fun lockAccount(until: Instant) {
        this.accountLockedUntil = until
        this.updatedAt = Instant.now()
    }
    
    fun unlockAccount() {
        this.accountLockedUntil = null
        this.updatedAt = Instant.now()
    }
    
    fun updatePassword(newPassword: String) {
        this.password = newPassword
        this.updatedAt = Instant.now()
    }
}
