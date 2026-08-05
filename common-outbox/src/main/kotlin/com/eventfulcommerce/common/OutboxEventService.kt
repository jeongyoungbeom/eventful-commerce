package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.OutboxEventRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class OutboxEventService(
    private val outboxEventRepository: OutboxEventRepository,
    private val retryPolicy: OutboxRetryPolicy,
    private val properties: OutboxPublisherProperties
) {
    fun record(events: List<OutboxEvent>) {
        outboxEventRepository.saveAll(events)
    }

    @Transactional
    fun claim(id: UUID, claimToken: UUID, now: Instant): Boolean =
        outboxEventRepository.claim(id, claimToken, now) == 1

    @Transactional
    fun markAsSent(id: UUID, claimToken: UUID): Boolean =
        outboxEventRepository.markSent(id, claimToken, Instant.now()) == 1

    @Transactional
    fun markAsFailed(event: OutboxEvent, claimToken: UUID, exception: Throwable, now: Instant = Instant.now()): Boolean {
        val attemptNumber = event.retryCount + 1
        val errorMessage = (exception.message ?: exception.javaClass.name).take(MAX_ERROR_MESSAGE_LENGTH)
        return outboxEventRepository.markForRetry(
            id = event.id,
            claimToken = claimToken,
            lastError = errorMessage,
            nextAttemptAt = retryPolicy.nextAttemptAt(attemptNumber, now),
            failedAt = now,
            maxRetries = properties.maxRetries
        ) == 1
    }

    @Transactional
    fun recoverExpiredClaims(now: Instant): Int {
        properties.validate()
        val cutoff = now.minusMillis(properties.claimTimeoutMs)
        return outboxEventRepository.findExpiredProcessing(
            cutoff = cutoff,
            pageable = PageRequest.of(0, properties.recoveryBatchSize)
        ).count { event ->
            val claimToken = event.processingToken ?: return@count false
            markAsFailed(event, claimToken, PublishLeaseExpiredException(event.id), now)
        }
    }

    @Transactional
    fun requeueFailed(id: UUID): Boolean =
        outboxEventRepository.requeueFailed(id, Instant.now()) == 1

    @Transactional(readOnly = true)
    fun findFailed(limit: Int): List<OutboxEvent> =
        outboxEventRepository.findByStatusOrderByFailedAtAsc(
            status = OutboxStatus.FAILED,
            pageable = PageRequest.of(0, limit.coerceIn(1, MAX_FAILED_EVENT_LIMIT))
        )

    private companion object {
        const val MAX_ERROR_MESSAGE_LENGTH = 2_000
        const val MAX_FAILED_EVENT_LIMIT = 200
    }
}

class PublishLeaseExpiredException(eventId: UUID) : RuntimeException("Outbox publish lease expired: eventId=$eventId")
