package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.collections.map

@Component
class OutboxEventService(
    private val outboxEventRepository: OutboxEventRepository,

) {
    fun recode(events: List<OutboxEvent>) {
        outboxEventRepository.saveAll(events)
    }

    @Transactional
    fun sent(id: UUID) {
        outboxEventRepository.updateSent(id = id, sentAt = Instant.now())
    }

    @Transactional
    fun failed(id: UUID, ex: Throwable, maxRetries: Int) {
        val massage = (ex.message ?: ex.javaClass.name).take(2000)
        outboxEventRepository.updateFailed(id, massage, maxRetries)
    }
}