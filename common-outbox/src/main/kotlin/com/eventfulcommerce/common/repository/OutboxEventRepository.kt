package com.eventfulcommerce.common.repository

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {
    fun countByStatus(status: OutboxStatus): Long
    fun findByStatusOrderByFailedAtAsc(status: OutboxStatus, pageable: Pageable): List<OutboxEvent>

    @Query(
        """
        SELECT e FROM OutboxEvent e
        WHERE e.status = :pending
          AND e.nextAttemptAt <= :now
        ORDER BY e.createdAt ASC
        """
    )
    fun findPublishable(
        @Param("now") now: Instant,
        pageable: Pageable,
        @Param("pending") pending: OutboxStatus = OutboxStatus.PENDING
    ): List<OutboxEvent>

    @Query(
        """
        SELECT e FROM OutboxEvent e
        WHERE e.status = :processing
          AND e.processingStartedAt <= :cutoff
        ORDER BY e.processingStartedAt ASC
        """
    )
    fun findExpiredProcessing(
        @Param("cutoff") cutoff: Instant,
        pageable: Pageable,
        @Param("processing") processing: OutboxStatus = OutboxStatus.PROCESSING
    ): List<OutboxEvent>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE OutboxEvent e
        SET e.status = :processing,
            e.processingToken = :claimToken,
            e.processingStartedAt = :now
        WHERE e.id = :id
          AND e.status = :pending
          AND e.nextAttemptAt <= :now
        """
    )
    fun claim(
        @Param("id") id: UUID,
        @Param("claimToken") claimToken: UUID,
        @Param("now") now: Instant,
        @Param("pending") pending: OutboxStatus = OutboxStatus.PENDING,
        @Param("processing") processing: OutboxStatus = OutboxStatus.PROCESSING
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE OutboxEvent e
        SET e.status = :sent,
            e.sentAt = :sentAt,
            e.lastError = NULL,
            e.processingToken = NULL,
            e.processingStartedAt = NULL
        WHERE e.id = :id
          AND e.status = :processing
          AND e.processingToken = :claimToken
        """
    )
    fun markSent(
        @Param("id") id: UUID,
        @Param("claimToken") claimToken: UUID,
        @Param("sentAt") sentAt: Instant,
        @Param("processing") processing: OutboxStatus = OutboxStatus.PROCESSING,
        @Param("sent") sent: OutboxStatus = OutboxStatus.SENT
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE OutboxEvent e
        SET e.retryCount = e.retryCount + 1,
            e.lastError = :lastError,
            e.nextAttemptAt = :nextAttemptAt,
            e.processingToken = NULL,
            e.processingStartedAt = NULL,
            e.failedAt = :failedAt,
            e.status = CASE WHEN (e.retryCount + 1) >= :maxRetries THEN :failed ELSE :pending END
        WHERE e.id = :id
          AND e.status = :processing
          AND e.processingToken = :claimToken
        """
    )
    fun markForRetry(
        @Param("id") id: UUID,
        @Param("claimToken") claimToken: UUID,
        @Param("lastError") lastError: String,
        @Param("nextAttemptAt") nextAttemptAt: Instant,
        @Param("failedAt") failedAt: Instant,
        @Param("maxRetries") maxRetries: Int,
        @Param("processing") processing: OutboxStatus = OutboxStatus.PROCESSING,
        @Param("pending") pending: OutboxStatus = OutboxStatus.PENDING,
        @Param("failed") failed: OutboxStatus = OutboxStatus.FAILED
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE OutboxEvent e
        SET e.status = :pending,
            e.retryCount = 0,
            e.lastError = NULL,
            e.nextAttemptAt = :now,
            e.processingToken = NULL,
            e.processingStartedAt = NULL,
            e.failedAt = NULL,
            e.requeueCount = e.requeueCount + 1,
            e.lastRequeuedAt = :now
        WHERE e.id = :id
          AND e.status = :failed
        """
    )
    fun requeueFailed(
        @Param("id") id: UUID,
        @Param("now") now: Instant,
        @Param("failed") failed: OutboxStatus = OutboxStatus.FAILED,
        @Param("pending") pending: OutboxStatus = OutboxStatus.PENDING
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        DELETE FROM OutboxEvent e
        WHERE e.status = :sent
          AND e.sentAt < :cutoff
        """
    )
    fun deleteSentBefore(
        @Param("cutoff") cutoff: Instant,
        @Param("sent") sent: OutboxStatus = OutboxStatus.SENT
    ): Int
}
