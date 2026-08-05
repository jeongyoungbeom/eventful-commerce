package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.DeadLetterEventRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DeadLetterEventServiceTest {
    private lateinit var repository: DeadLetterEventRepository
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    private lateinit var service: DeadLetterEventService

    @BeforeEach
    fun setUp() {
        repository = mockk()
        kafkaTemplate = mockk()
        service = DeadLetterEventService(repository, kafkaTemplate)
    }

    @Test
    fun `같은 원본 레코드가 다시 도착하면 DLT 보관을 중복하지 않는다`() {
        val record = ConsumerRecord("payment-events.DLT", 2, 31L, "order-1", "payload")
        every {
            repository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(
                "payment-events", 2, 31L
            )
        } returns true

        service.archive(record)

        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `보류 DLT는 원본 토픽에 재발행한 뒤 replay 상태로 바꾼다`() {
        val event = DeadLetterEvent(
            originalTopic = "payment-events",
            originalPartition = 0,
            originalOffset = 7L,
            recordKey = "order-1",
            payload = "payload"
        )
        val eventId = UUID.randomUUID()
        event.id = eventId
        every { repository.findByIdForUpdate(eventId) } returns event
        every { kafkaTemplate.send("payment-events", "order-1", "payload") } returns CompletableFuture.completedFuture(
            mockk<SendResult<String, String>>()
        )

        val replayed = service.replay(eventId)

        assertTrue(replayed)
        assertEquals(DeadLetterEventStatus.REPLAYED, event.status)
        assertEquals(1, event.replayCount)
        verify(exactly = 1) { kafkaTemplate.send("payment-events", "order-1", "payload") }
    }

    @Test
    fun `이미 재처리된 DLT는 다시 발행하지 않는다`() {
        val eventId = UUID.randomUUID()
        val event = DeadLetterEvent(
            originalTopic = "payment-events",
            originalPartition = 0,
            originalOffset = 7L,
            payload = "payload",
            status = DeadLetterEventStatus.REPLAYED
        )
        event.id = eventId
        every { repository.findByIdForUpdate(eventId) } returns event

        val replayed = service.replay(eventId)

        assertFalse(replayed)
        verify(exactly = 0) { kafkaTemplate.send(any<String>(), any<String>()) }
    }
}
