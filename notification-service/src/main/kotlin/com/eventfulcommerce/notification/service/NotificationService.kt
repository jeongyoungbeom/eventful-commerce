package com.eventfulcommerce.notification.service

import com.eventfulcommerce.notification.domain.NotificationType
import com.eventfulcommerce.notification.domain.entity.Notification
import com.eventfulcommerce.notification.repository.NotificationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val telegramService: TelegramService
) {

    /**
     * 알림 생성 및 전송
     */
    @Transactional
    fun createAndSend(
        userId: UUID,
        type: NotificationType,
        title: String,
        message: String,
        orderId: UUID? = null
    ): Notification {
        // 1. 텔레그램 전송 먼저 시도
        val telegramMessageId = telegramService.sendNotification(userId, message)
        
        // 2. 결과와 함께 한 번에 DB 저장
        val notification = Notification(
            userId = userId,
            type = type,
            title = title,
            message = message,
            orderId = orderId,
            sentToTelegram = telegramMessageId != null,
            telegramMessageId = telegramMessageId
        )
        
        val savedNotification = notificationRepository.save(notification)
        
        logger.info { 
            "📝 알림 저장 완료: userId=$userId, type=$type, orderId=$orderId, " +
            "telegram=${if (telegramMessageId != null) "sent" else "failed"}" 
        }
        
        return savedNotification
    }

    /**
     * 사용자 알림 목록 조회
     */
    fun getUserNotifications(userId: UUID): List<Notification> {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId)
    }

    /**
     * 알림 읽음 처리
     */
    @Transactional
    fun markAsRead(notificationId: UUID): Boolean {
        val notification = notificationRepository.findById(notificationId).orElse(null)
            ?: return false
        
        notification.isRead = true
        notificationRepository.save(notification)
        
        return true
    }

    /**
     * 읽지 않은 알림 개수
     */
    fun getUnreadCount(userId: UUID): Long {
        return notificationRepository.countByUserIdAndIsReadFalse(userId)
    }
}
