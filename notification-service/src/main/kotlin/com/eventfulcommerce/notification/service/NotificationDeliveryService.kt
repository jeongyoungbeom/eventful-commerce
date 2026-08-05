package com.eventfulcommerce.notification.service

import com.eventfulcommerce.notification.domain.NotificationDeliveryStatus
import com.eventfulcommerce.notification.repository.NotificationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class NotificationDeliveryService(
    private val notificationRepository: NotificationRepository,
    private val telegramService: TelegramService,
    @Value("\${notification.delivery.max-attempts:10}")
    private val maxAttempts: Int = 10,
    @Value("\${notification.delivery.initial-backoff-seconds:10}")
    private val initialBackoffSeconds: Long = 10,
    @Value("\${notification.delivery.max-backoff-seconds:3600}")
    private val maxBackoffSeconds: Long = 3600
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be greater than zero" }
        require(initialBackoffSeconds > 0) { "initialBackoffSeconds must be greater than zero" }
        require(maxBackoffSeconds >= initialBackoffSeconds) {
            "maxBackoffSeconds must be greater than or equal to initialBackoffSeconds"
        }
    }

    @Transactional
    fun deliver(notificationId: UUID, now: Instant = Instant.now()) {
        val notification = notificationRepository.findByIdForDelivery(notificationId) ?: return

        if (notification.sentToTelegram) {
            notification.synchronizeLegacyDelivery(now)
            return
        }
        if (notification.deliveryStatus == NotificationDeliveryStatus.SENT ||
            notification.deliveryStatus == NotificationDeliveryStatus.FAILED ||
            notification.nextDeliveryAttemptAt.isAfter(now)
        ) {
            return
        }

        val delivery = runCatching {
            telegramService.sendNotification(notification.userId, notification.message)
        }
        val telegramMessageId = delivery.getOrNull()

        if (telegramMessageId != null) {
            notification.markDeliverySucceeded(telegramMessageId, now)
            logger.info {
                "Notification delivered: notificationId=$notificationId, attempt=${notification.deliveryAttempts}"
            }
            return
        }

        val retryAt = now.plusSeconds(nextBackoffSeconds(notification.deliveryAttempts))
        val error = delivery.exceptionOrNull()?.message ?: "Telegram delivery returned no message ID"
        notification.markDeliveryFailed(error, retryAt, maxAttempts)
        logger.warn {
            "Notification delivery failed: notificationId=$notificationId, " +
                "attempt=${notification.deliveryAttempts}, status=${notification.deliveryStatus}, retryAt=$retryAt"
        }
    }

    private fun nextBackoffSeconds(attemptsAlreadyMade: Int): Long {
        var delay = initialBackoffSeconds
        repeat(attemptsAlreadyMade.coerceAtMost(62)) {
            delay = if (delay >= maxBackoffSeconds / 2) {
                maxBackoffSeconds
            } else {
                (delay * 2).coerceAtMost(maxBackoffSeconds)
            }
        }
        return delay
    }
}
