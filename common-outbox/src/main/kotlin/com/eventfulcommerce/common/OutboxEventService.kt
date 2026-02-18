package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.OutboxEventRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class OutboxEventService(
    private val outboxEventRepository: OutboxEventRepository,

) {
    fun record(events: List<OutboxEvent>) {
        outboxEventRepository.saveAll(events)
    }

    @Transactional
    fun markAsSent(id: UUID) {
        outboxEventRepository.updateSent(id = id, sentAt = Instant.now())
    }

    @Transactional
    fun markAsFailed(id: UUID, ex: Throwable, maxRetries: Int) {
        val errorMessage = (ex.message ?: ex.javaClass.name).take(2000)
        outboxEventRepository.updateFailed(id, errorMessage, maxRetries)
    }
}