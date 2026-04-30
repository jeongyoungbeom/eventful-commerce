package com.eventfulcommerce.user.domain.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sellers")
class Seller(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    
    @Column(unique = true, nullable = false)
    val userId: UUID,
    
    @Column(nullable = false, length = 255)
    var businessName: String,
    
    @Column(unique = true, nullable = false, length = 20)
    val businessNumber: String,
    
    @Column(nullable = false, length = 50)
    var bankAccount: String,
    
    @Column(nullable = false, length = 10)
    var bankCode: String,
    
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    fun updateBankInfo(bankAccount: String, bankCode: String) {
        this.bankAccount = bankAccount
        this.bankCode = bankCode
        this.updatedAt = Instant.now()
    }
    
    fun updateBusinessName(businessName: String) {
        this.businessName = businessName
        this.updatedAt = Instant.now()
    }
}
