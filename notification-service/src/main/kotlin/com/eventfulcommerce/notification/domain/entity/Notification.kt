package com.eventfulcommerce.notification.domain.entity

import com.eventfulcommerce.notification.domain.NotificationDeliveryStatus
import com.eventfulcommerce.notification.domain.NotificationType
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "notifications",
    indexes = [
        Index(
            name = "idx_notifications_delivery_due",
            columnList = "delivery_status,next_delivery_attempt_at"
        )
    ]
)
class Notification(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    
    @Column(nullable = false)
    val userId: UUID,
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: NotificationType,
    
    @Column(nullable = false)
    val title: String,
    
    @Column(nullable = false, length = 1000)
    val message: String,
    
    @Column
    val orderId: UUID? = null,
    
    @Column(nullable = false)
    var isRead: Boolean = false,
    
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
    
    @Column(nullable = false)
    var sentToTelegram: Boolean = false,
    
    @Column
    var telegramMessageId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, columnDefinition = "varchar(32) default 'PENDING'")
    var deliveryStatus: NotificationDeliveryStatus = NotificationDeliveryStatus.PENDING,

    @Column(nullable = false, columnDefinition = "integer default 0")
    var deliveryAttempts: Int = 0,

    @Column(
        name = "next_delivery_attempt_at",
        nullable = false,
        columnDefinition = "timestamp with time zone default CURRENT_TIMESTAMP"
    )
    var nextDeliveryAttemptAt: Instant = Instant.now(),

    @Column(length = 1000)
    var lastDeliveryError: String? = null,

    @Column
    var deliveredAt: Instant? = null
) {
    fun synchronizeLegacyDelivery(now: Instant) {
        if (!sentToTelegram || deliveryStatus == NotificationDeliveryStatus.SENT) return
        deliveryStatus = NotificationDeliveryStatus.SENT
        deliveredAt = deliveredAt ?: now
        lastDeliveryError = null
    }

    fun markDeliverySucceeded(messageId: String, now: Instant) {
        deliveryAttempts += 1
        sentToTelegram = true
        telegramMessageId = messageId
        deliveryStatus = NotificationDeliveryStatus.SENT
        deliveredAt = now
        nextDeliveryAttemptAt = now
        lastDeliveryError = null
    }

    fun markDeliveryFailed(error: String, retryAt: Instant, maxAttempts: Int) {
        deliveryAttempts += 1
        deliveryStatus = if (deliveryAttempts >= maxAttempts) {
            NotificationDeliveryStatus.FAILED
        } else {
            NotificationDeliveryStatus.RETRY
        }
        nextDeliveryAttemptAt = retryAt
        lastDeliveryError = error.take(1000)
    }
}
