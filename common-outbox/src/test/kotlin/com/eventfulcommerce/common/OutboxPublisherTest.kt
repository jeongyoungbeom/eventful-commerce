package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.UUID
import java.util.concurrent.CompletableFuture

class OutboxPublisherTest {
    private val outboxEventRepository = mockk<OutboxEventRepository>()
    private val outboxEventService = mockk<OutboxEventService>()
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
    private val objectMapper = mockk<ObjectMapper>()
    private val properties = OutboxPublisherProperties().apply {
        batchSize = 10
        maxInFlight = 10
    }

    @Test
    fun `claim에 실패한 이벤트는 Kafka로 전송하지 않는다`() {
        val event = outboxEvent()
        every { outboxEventService.recoverExpiredClaims(any()) } returns 0
        every { outboxEventRepository.findPublishable(any(), any(), any()) } returns listOf(event)
        every { outboxEventService.claim(event.id, any(), any()) } returns false

        publisher().publishPending()

        verify(exactly = 0) { kafkaTemplate.send(any<String>(), any<String>(), any<String>()) }
    }

    @Test
    fun `claim을 얻고 Kafka 전송이 성공하면 같은 token으로 SENT 처리한다`() {
        val event = outboxEvent()
        val future = CompletableFuture.completedFuture(mockk<SendResult<String, String>>())
        every { outboxEventService.recoverExpiredClaims(any()) } returns 0
        every { outboxEventRepository.findPublishable(any(), any(), any()) } returns listOf(event)
        every { outboxEventService.claim(event.id, any(), any()) } returns true
        every { objectMapper.writeValueAsString(any()) } returns "{}"
        every { kafkaTemplate.send("order-events", event.aggregateId.toString(), "{}") } returns future
        every { outboxEventService.markAsSent(event.id, any()) } returns true

        publisher().publishPending()

        verify(exactly = 1) { outboxEventService.markAsSent(event.id, any()) }
    }

    private fun publisher() = OutboxPublisher(
        outboxEventRepository,
        outboxEventService,
        kafkaTemplate,
        objectMapper,
        properties,
        "order-events"
    )

    private fun outboxEvent(): OutboxEvent = OutboxEvent(
        aggregateType = "ORDER",
        aggregateId = UUID.randomUUID(),
        eventType = "ORDER_CREATED",
        payload = "{}"
    ).apply { id = UUID.randomUUID() }
}
