package com.eventfulcommerce.user.domain.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "audit_logs",
    indexes = [
        Index(name = "idx_audit_logs_user_id", columnList = "userId"),
        Index(name = "idx_audit_logs_created_at", columnList = "createdAt")
    ]
)
class AuditLog(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    
    @Column
    val userId: UUID? = null,
    
    @Column(nullable = false, length = 50)
    val action: String,  // LOGIN, LOGOUT, SIGNUP, PASSWORD_CHANGE, etc.
    
    @Column(length = 50)
    val ipAddress: String? = null,
    
    @Column(columnDefinition = "TEXT")
    val userAgent: String? = null,
    
    @Column(nullable = false)
    val success: Boolean,
    
    @Column(columnDefinition = "TEXT")
    val failureReason: String? = null,
    
    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)
