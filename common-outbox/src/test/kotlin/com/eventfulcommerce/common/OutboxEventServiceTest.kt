package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.OutboxEventRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class OutboxEventServiceTest {
    private lateinit var outboxEventRepository: OutboxEventRepository
    private lateinit var outboxEventService: OutboxEventService

    @BeforeEach
    fun setUp() {
        outboxEventRepository = mockk()
        outboxEventService = OutboxEventService(outboxEventRepository)
    }

    @Test
    fun `record는 전달받은 이벤트 목록을 그대로 저장한다`() {
        val events = listOf(outboxEvent("ORDER_RESERVED"), outboxEvent("ORDER_CONFIRMED"))

        every { outboxEventRepository.saveAll(any<List<OutboxEvent>>()) } returns events

        outboxEventService.record(events)

        verify(exactly = 1) { outboxEventRepository.saveAll(events) }
    }

    @Test
    fun `markAsSent는 이벤트를 SENT 상태로 전이하도록 repository에 위임한다`() {
        val eventId = UUID.randomUUID()

        every { outboxEventRepository.updateSent(eventId, any(), any()) } returns 1

        outboxEventService.markAsSent(eventId)

        verify(exactly = 1) {
            outboxEventRepository.updateSent(
                id = eventId,
                status = OutboxStatus.SENT,
                sentAt = any()
            )
        }
    }

    @Test
    fun `markAsFailed는 에러 메시지를 2000자로 잘라 실패 처리를 위임한다`() {
        val eventId = UUID.randomUUID()
        val longMessage = "x".repeat(2_100)

        every {
            outboxEventRepository.updateFailed(eventId, any<String>(), 3, any())
        } just Runs

        outboxEventService.markAsFailed(eventId, RuntimeException(longMessage), maxRetries = 3)

        verify(exactly = 1) {
            outboxEventRepository.updateFailed(
                id = eventId,
                lastError = match<String> { it.length == 2_000 && it.all { ch -> ch == 'x' } },
                maxRetries = 3,
                failed = OutboxStatus.FAILED
            )
        }
    }

    @Test
    fun `markAsFailed는 예외 메시지가 없으면 예외 클래스명을 에러 메시지로 사용한다`() {
        val eventId = UUID.randomUUID()
        val exception = NullPointerException()

        every {
            outboxEventRepository.updateFailed(eventId, any<String>(), 5, any())
        } just Runs

        outboxEventService.markAsFailed(eventId, exception, maxRetries = 5)

        verify(exactly = 1) {
            outboxEventRepository.updateFailed(
                id = eventId,
                lastError = NullPointerException::class.java.name,
                maxRetries = 5,
                failed = OutboxStatus.FAILED
            )
        }
    }

    private fun outboxEvent(eventType: String) = OutboxEvent(
        aggregateType = "ORDER",
        aggregateId = UUID.randomUUID(),
        eventType = eventType,
        payload = "{}",
        status = OutboxStatus.PENDING
    )
}
