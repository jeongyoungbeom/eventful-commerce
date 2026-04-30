package com.eventfulcommerce.user.domain.repository

import com.eventfulcommerce.user.domain.entity.AuditLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuditLogRepository : JpaRepository<AuditLog, UUID> {
    
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<AuditLog>
    
    fun findByActionAndCreatedAtAfter(action: String, after: Instant): List<AuditLog>
    
    fun countByUserIdAndActionAndSuccessFalseAndCreatedAtAfter(
        userId: UUID,
        action: String,
        after: Instant
    ): Long
}
