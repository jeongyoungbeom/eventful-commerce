package com.eventfulcommerce.common.repository

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.*

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {
    fun findByStatusOrderByCreatedAtAsc(status: OutboxStatus, pageable: Pageable): List<OutboxEvent>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE OutboxEvent e
        SET e.status = :status,
            e.sentAt = :sentAt,
            e.lastError = null
            WHERE e.id = :id
    """
    )
    fun updateSent(
        @Param("id") id: UUID,
        @Param("status") status: OutboxStatus = OutboxStatus.SENT,
        @Param("sentAt") sentAt: Instant
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE OutboxEvent e
        SET e.retryCount = e.retryCount + 1,
         e.lastError = :lastError,
         e.status = case when (e.retryCount + 1) >= :maxRetries then :failed else e.status end
         WHERE e.id = :id
    """
    )
    fun updateFailed(
        @Param("id") id: UUID,
        @Param("lastError") lastError: String,
        @Param("maxRetries") maxRetries: Int,
        @Param("failed") failed: OutboxStatus = OutboxStatus.FAILED
    )
}