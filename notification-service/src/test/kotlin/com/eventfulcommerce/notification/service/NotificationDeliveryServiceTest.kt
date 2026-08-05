package com.eventfulcommerce.notification.service

import com.eventfulcommerce.notification.domain.NotificationDeliveryStatus
import com.eventfulcommerce.notification.domain.NotificationType
import com.eventfulcommerce.notification.domain.entity.Notification
import com.eventfulcommerce.notification.repository.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class NotificationDeliveryServiceTest {
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var telegramService: TelegramService
    private lateinit var service: NotificationDeliveryService

    @BeforeEach
    fun setUp() {
        notificationRepository = mockk()
        telegramService = mockk()
        service = NotificationDeliveryService(
            notificationRepository = notificationRepository,
            telegramService = telegramService,
            maxAttempts = 3,
            initialBackoffSeconds = 10,
            maxBackoffSeconds = 60
        )
    }

    @Test
    fun `successful delivery changes a pending task to sent`() {
        val now = Instant.parse("2026-08-05T00:00:00Z")
        val notification = notification(now)
        every { notificationRepository.findByIdForDelivery(notification.id) } returns notification
        every { telegramService.sendNotification(notification.userId, notification.message) } returns "12345"

        service.deliver(notification.id, now)

        assertEquals(NotificationDeliveryStatus.SENT, notification.deliveryStatus)
        assertEquals(1, notification.deliveryAttempts)
        assertTrue(notification.sentToTelegram)
        assertEquals("12345", notification.telegramMessageId)
        assertEquals(now, notification.deliveredAt)
    }

    @Test
    fun `failed delivery is retained with exponential retry time`() {
        val now = Instant.parse("2026-08-05T00:00:00Z")
        val notification = notification(now)
        every { notificationRepository.findByIdForDelivery(notification.id) } returns notification
        every { telegramService.sendNotification(notification.userId, notification.message) } returns null

        service.deliver(notification.id, now)

        assertEquals(NotificationDeliveryStatus.RETRY, notification.deliveryStatus)
        assertEquals(1, notification.deliveryAttempts)
        assertEquals(now.plusSeconds(10), notification.nextDeliveryAttemptAt)
        assertFalse(notification.sentToTelegram)
    }

    @Test
    fun `delivery stops after the configured maximum attempts`() {
        val now = Instant.parse("2026-08-05T00:00:00Z")
        val notification = notification(now).apply { deliveryAttempts = 2 }
        every { notificationRepository.findByIdForDelivery(notification.id) } returns notification
        every { telegramService.sendNotification(notification.userId, notification.message) } returns null

        service.deliver(notification.id, now)

        assertEquals(NotificationDeliveryStatus.FAILED, notification.deliveryStatus)
        assertEquals(3, notification.deliveryAttempts)
    }

    @Test
    fun `legacy telegram delivery is synchronized without sending again`() {
        val now = Instant.parse("2026-08-05T00:00:00Z")
        val notification = notification(now).apply {
            sentToTelegram = true
            telegramMessageId = "legacy-message"
        }
        every { notificationRepository.findByIdForDelivery(notification.id) } returns notification

        service.deliver(notification.id, now)

        assertEquals(NotificationDeliveryStatus.SENT, notification.deliveryStatus)
        verify(exactly = 0) { telegramService.sendNotification(any(), any()) }
    }

    private fun notification(now: Instant) = Notification(
        userId = UUID.randomUUID(),
        type = NotificationType.PAYMENT_COMPLETED,
        title = "Payment completed",
        message = "Your payment was completed.",
        orderId = UUID.randomUUID(),
        nextDeliveryAttemptAt = now
    )
}
