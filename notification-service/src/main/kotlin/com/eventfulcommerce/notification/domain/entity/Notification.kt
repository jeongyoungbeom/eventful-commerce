package com.eventfulcommerce.notification.domain.entity

import com.eventfulcommerce.notification.domain.NotificationType
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "notifications")
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
    var telegramMessageId: String? = null
)
