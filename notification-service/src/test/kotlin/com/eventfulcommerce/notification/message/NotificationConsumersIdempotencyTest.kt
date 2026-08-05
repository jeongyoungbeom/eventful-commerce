package com.eventfulcommerce.notification.message

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OrderCanceledPayload
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.ShippingStartedPayload
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.notification.domain.NotificationType
import com.eventfulcommerce.notification.domain.entity.Notification
import com.eventfulcommerce.notification.service.NotificationService
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class NotificationConsumersIdempotencyTest {
    private lateinit var processedEventRepository: ProcessedEventRepository
    private lateinit var notificationService: NotificationService
    private lateinit var idempotencyHandler: IdempotencyHandler

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setUp() {
        processedEventRepository = mockk()
        notificationService = mockk()
        idempotencyHandler = IdempotencyHandler(processedEventRepository)
        every {
            notificationService.create(any(), any(), any(), any(), any())
        } returns mockk<Notification>()
    }

    @Test
    fun `중복 결제 이벤트는 알림을 한 번만 생성한다`() {
        val eventId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        every { processedEventRepository.insertIfAbsent(eventId) } returnsMany listOf(1, 0)
        val consumer = PaymentEventsConsumer(objectMapper, notificationService, idempotencyHandler)
        val value = message(
            eventId,
            "PAYMENT_COMPLETED",
            PaymentCompletedPayload(
                paymentId = UUID.randomUUID(),
                orderId = orderId,
                userId = userId,
                amount = 10_000,
                completedAt = Instant.now()
            )
        )

        consumer.consume(value)
        consumer.consume(value)

        verify(exactly = 1) {
            notificationService.create(
                userId,
                NotificationType.PAYMENT_COMPLETED,
                any(),
                any(),
                orderId
            )
        }
    }

    @Test
    fun `중복 배송 이벤트는 알림을 한 번만 생성한다`() {
        val eventId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        every { processedEventRepository.insertIfAbsent(eventId) } returnsMany listOf(1, 0)
        val consumer = ShippingEventsConsumer(objectMapper, notificationService, idempotencyHandler)
        val value = message(
            eventId,
            "SHIPPING_STARTED",
            ShippingStartedPayload(orderId, userId, "TRK123")
        )

        consumer.consume(value)
        consumer.consume(value)

        verify(exactly = 1) {
            notificationService.create(
                userId,
                NotificationType.SHIPPING_STARTED,
                any(),
                any(),
                orderId
            )
        }
    }

    @Test
    fun `중복 주문 이벤트는 알림을 한 번만 생성한다`() {
        val eventId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        every { processedEventRepository.insertIfAbsent(eventId) } returnsMany listOf(1, 0)
        val consumer = OrderEventsConsumer(objectMapper, notificationService, idempotencyHandler)
        val value = message(
            eventId,
            "ORDER_CANCELED",
            OrderCanceledPayload(orderId, userId, "payment failed")
        )

        consumer.consume(value)
        consumer.consume(value)

        verify(exactly = 1) {
            notificationService.create(
                userId,
                NotificationType.ORDER_CANCELED,
                any(),
                any(),
                orderId
            )
        }
    }

    @Test
    fun `unsupported notification event is not claimed as processed`() {
        val eventId = UUID.randomUUID()
        val consumer = PaymentEventsConsumer(objectMapper, notificationService, idempotencyHandler)

        consumer.consume(message(eventId, "PAYMENT_UNKNOWN", emptyMap<String, String>()))

        verify(exactly = 0) { processedEventRepository.insertIfAbsent(eventId) }
    }

    private fun message(eventId: UUID, eventType: String, payload: Any): String =
        objectMapper.writeValueAsString(
            OutboxEventMessage(
                eventId = eventId,
                aggregateType = "TEST",
                aggregateId = UUID.randomUUID(),
                eventType = eventType,
                occurredAt = Instant.now(),
                payload = objectMapper.writeValueAsString(payload)
            )
        )
}
