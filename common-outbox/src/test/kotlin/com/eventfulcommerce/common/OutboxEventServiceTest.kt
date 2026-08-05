package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.OutboxEventRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue

class OutboxEventServiceTest {
    private lateinit var outboxEventRepository: OutboxEventRepository
    private lateinit var outboxEventService: OutboxEventService
    private lateinit var properties: OutboxPublisherProperties

    @BeforeEach
    fun setUp() {
        outboxEventRepository = mockk()
        properties = OutboxPublisherProperties().apply { maxRetries = 3 }
        outboxEventService = OutboxEventService(
            outboxEventRepository,
            OutboxRetryPolicy(properties),
            properties
        )
    }

    @Test
    fun `record는 전달받은 이벤트 목록을 그대로 저장한다`() {
        val events = listOf(outboxEvent("ORDER_RESERVED"), outboxEvent("ORDER_CONFIRMED"))

        every { outboxEventRepository.saveAll(any<List<OutboxEvent>>()) } returns events

        outboxEventService.record(events)

        verify(exactly = 1) { outboxEventRepository.saveAll(events) }
    }

    @Test
    fun `claim은 PENDING 이벤트 하나를 처리 임대로 원자적으로 전이한다`() {
        val eventId = UUID.randomUUID()
        val claimToken = UUID.randomUUID()
        val now = Instant.parse("2026-08-05T00:00:00Z")
        every { outboxEventRepository.claim(eventId, claimToken, now, any(), any()) } returns 1

        assertTrue(outboxEventService.claim(eventId, claimToken, now))

        verify(exactly = 1) {
            outboxEventRepository.claim(
                id = eventId,
                claimToken = claimToken,
                now = now,
                pending = OutboxStatus.PENDING,
                processing = OutboxStatus.PROCESSING
            )
        }
    }

    @Test
    fun `markAsSent는 같은 claim token을 가진 처리 건만 SENT로 전이한다`() {
        val eventId = UUID.randomUUID()
        val claimToken = UUID.randomUUID()
        every { outboxEventRepository.markSent(eventId, claimToken, any(), any(), any()) } returns 1

        assertTrue(outboxEventService.markAsSent(eventId, claimToken))

        verify(exactly = 1) {
            outboxEventRepository.markSent(
                id = eventId,
                claimToken = claimToken,
                sentAt = any(),
                processing = OutboxStatus.PROCESSING,
                sent = OutboxStatus.SENT
            )
        }
    }

    @Test
    fun `markAsFailed는 에러를 잘라 지수 백오프 재시도로 전이한다`() {
        val now = Instant.parse("2026-08-05T00:00:00Z")
        val event = outboxEvent(retryCount = 1)
        val claimToken = UUID.randomUUID()
        val longMessage = "x".repeat(2_100)
        every {
            outboxEventRepository.markForRetry(
                event.id,
                claimToken,
                any(),
                any(),
                now,
                3,
                any(),
                any(),
                any()
            )
        } returns 1

        assertTrue(outboxEventService.markAsFailed(event, claimToken, RuntimeException(longMessage), now))

        verify(exactly = 1) {
            outboxEventRepository.markForRetry(
                id = event.id,
                claimToken = claimToken,
                lastError = match<String> { it.length == 2_000 && it.all { character -> character == 'x' } },
                nextAttemptAt = now.plusMillis(2_000),
                failedAt = now,
                maxRetries = 3,
                processing = OutboxStatus.PROCESSING,
                pending = OutboxStatus.PENDING,
                failed = OutboxStatus.FAILED
            )
        }
    }

    @Test
    fun `recoverExpiredClaims는 만료된 처리 임대만 실패 재시도로 돌린다`() {
        val now = Instant.parse("2026-08-05T00:01:00Z")
        val expiredEvent = outboxEvent(retryCount = 0).apply {
            status = OutboxStatus.PROCESSING
            processingToken = UUID.randomUUID()
            processingStartedAt = now.minusSeconds(31)
        }
        every { outboxEventRepository.findExpiredProcessing(any(), any(), any()) } returns listOf(expiredEvent)
        every {
            outboxEventRepository.markForRetry(
                expiredEvent.id,
                expiredEvent.processingToken!!,
                any(),
                now.plusMillis(1_000),
                now,
                3,
                any(),
                any(),
                any()
            )
        } returns 1

        assertTrue(outboxEventService.recoverExpiredClaims(now) == 1)

        verify(exactly = 1) {
            outboxEventRepository.markForRetry(
                id = expiredEvent.id,
                claimToken = expiredEvent.processingToken!!,
                lastError = match { it.contains("lease expired") },
                nextAttemptAt = now.plusMillis(1_000),
                failedAt = now,
                maxRetries = 3,
                processing = OutboxStatus.PROCESSING,
                pending = OutboxStatus.PENDING,
                failed = OutboxStatus.FAILED
            )
        }
    }

    @Test
    fun `requeueFailed는 FAILED 이벤트만 수동 재처리 대기열로 돌린다`() {
        val eventId = UUID.randomUUID()
        every { outboxEventRepository.requeueFailed(eventId, any(), any(), any()) } returns 1

        assertTrue(outboxEventService.requeueFailed(eventId))

        verify(exactly = 1) {
            outboxEventRepository.requeueFailed(
                id = eventId,
                now = any(),
                failed = OutboxStatus.FAILED,
                pending = OutboxStatus.PENDING
            )
        }
    }

    private fun outboxEvent(eventType: String = "ORDER_RESERVED", retryCount: Int = 0): OutboxEvent =
        OutboxEvent(
            aggregateType = "ORDER",
            aggregateId = UUID.randomUUID(),
            eventType = eventType,
            payload = "{}",
            retryCount = retryCount
        ).apply { id = UUID.randomUUID() }
}
