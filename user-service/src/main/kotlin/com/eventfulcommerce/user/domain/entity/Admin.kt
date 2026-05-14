package com.eventfulcommerce.user.domain.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "admins")
class Admin(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    override val id: UUID = UUID.randomUUID(),

    @Column(unique = true, nullable = false, length = 255)
    val email: String,

    @Column(nullable = false, length = 255)
    var password: String,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column
    override var accountLockedUntil: Instant? = null,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
) : Lockable {

    override fun isAccountLocked(): Boolean =
        accountLockedUntil?.let { it.isAfter(Instant.now()) } ?: false

    override fun lockAccount(until: Instant) {
        this.accountLockedUntil = until
        this.updatedAt = Instant.now()
    }

    override fun unlockAccount() {
        this.accountLockedUntil = null
        this.updatedAt = Instant.now()
    }

    fun updatePassword(newPassword: String) {
        this.password = newPassword
        this.updatedAt = Instant.now()
    }
}
