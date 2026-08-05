package com.eventfulcommerce.notification.service

import com.eventfulcommerce.notification.domain.NotificationDeliveryStatus
import com.eventfulcommerce.notification.repository.NotificationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Component
class NotificationDeliveryScheduler(
    private val notificationRepository: NotificationRepository,
    private val notificationDeliveryService: NotificationDeliveryService
) {
    @Scheduled(fixedDelayString = "\${notification.delivery.fixed-delay-ms:5000}")
    fun deliverPendingNotifications() {
        val now = Instant.now()
        val notificationIds = notificationRepository
            .findTop100ByDeliveryStatusInAndNextDeliveryAttemptAtLessThanEqualOrderByCreatedAtAsc(
                listOf(NotificationDeliveryStatus.PENDING, NotificationDeliveryStatus.RETRY),
                now
            )
            .map { it.id }

        notificationIds.forEach { notificationId ->
            runCatching { notificationDeliveryService.deliver(notificationId) }
                .onFailure { exception ->
                    logger.error(exception) {
                        "Notification delivery transaction failed: notificationId=$notificationId"
                    }
                }
        }
    }
}
