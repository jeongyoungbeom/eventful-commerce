package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger { }

@Component
class OutboxPublisher(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventService: OutboxEventService,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${outbox.topic}")
    private val topic: String
) {
    private val batchSize = 50
    private val maxRetries = 10

    private val inFlight = AtomicInteger(0)
    private val maxInFlight = 200

    @Scheduled(fixedDelayString = "200")
    @Transactional
    fun publishPending() {
        if (inFlight.get() >= maxInFlight) return

        val outboxEvents =
            outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING, PageRequest.of(0, batchSize))

        outboxEvents.forEach { event ->
            if (inFlight.incrementAndGet() > maxInFlight) {
                inFlight.decrementAndGet()
                return
            }

            val outboxEventMessage = OutboxEventMessage(
                eventId = event.id,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                eventType = event.eventType,
                occurredAt = event.createdAt,
                payload = event.payload
            )
            logger.info { "퍼블리싱 -> " + event.payload}
            val valueAsString = objectMapper.writeValueAsString(outboxEventMessage)

            kafkaTemplate.send(topic, event.aggregateId.toString(), valueAsString)
                .whenComplete { _, ex ->
                    try {
                        if (ex == null) outboxEventService.sent(event.id)
                        else outboxEventService.failed(event.id, ex, maxRetries)
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
        }
    }
}