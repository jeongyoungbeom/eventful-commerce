package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger { }

@Component
@ConditionalOnProperty(prefix = "outbox.publisher", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OutboxPublisher(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventService: OutboxEventService,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val properties: OutboxPublisherProperties,
    @Value("\${outbox.topic}")
    private val topic: String
) {
    private val inFlight = AtomicInteger(0)

    @Scheduled(fixedDelayString = "\${outbox.publisher.polling-ms:200}")
    fun publishPending() {
        properties.validate()
        val now = Instant.now()
        outboxEventService.recoverExpiredClaims(now)

        val capacity = properties.maxInFlight - inFlight.get()
        if (capacity <= 0) return

        val candidates = outboxEventRepository.findPublishable(
            now = now,
            pageable = PageRequest.of(0, minOf(properties.batchSize, capacity))
        )

        candidates.forEach { event ->
            if (inFlight.incrementAndGet() > properties.maxInFlight) {
                inFlight.decrementAndGet()
                return@forEach
            }

            val claimToken = UUID.randomUUID()
            if (!outboxEventService.claim(event.id, claimToken, now)) {
                inFlight.decrementAndGet()
                return@forEach
            }
            publish(event, claimToken)
        }
    }

    private fun publish(event: OutboxEvent, claimToken: UUID) {
        val message = try {
            objectMapper.writeValueAsString(
                OutboxEventMessage(
                    eventId = event.id,
                    aggregateType = event.aggregateType,
                    aggregateId = event.aggregateId,
                    eventType = event.eventType,
                    occurredAt = event.createdAt,
                    payload = event.payload
                )
            )
        } catch (exception: Exception) {
            completeFailure(event, claimToken, exception)
            return
        }

        try {
            kafkaTemplate.send(topic, event.aggregateId.toString(), message)
                .whenComplete { _, exception ->
                    try {
                        if (exception == null) {
                            outboxEventService.markAsSent(event.id, claimToken)
                        } else {
                            outboxEventService.markAsFailed(event, claimToken, exception)
                        }
                    } catch (updateException: Exception) {
                        logger.error(updateException) {
                            "Outbox event state update failed: eventId=${event.id}"
                        }
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
        } catch (exception: Exception) {
            completeFailure(event, claimToken, exception)
        }
    }

    private fun completeFailure(event: OutboxEvent, claimToken: UUID, exception: Throwable) {
        try {
            outboxEventService.markAsFailed(event, claimToken, exception)
        } catch (updateException: Exception) {
            logger.error(updateException) {
                "Outbox event failure state update failed: eventId=${event.id}"
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }
}
