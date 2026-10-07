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
    private val notificationRepository: NotificationRepository
) {

    /**
     * 알림과 비동기 전송 작업을 같은 DB 행으로 저장한다.
     */
    @Transactional
    fun create(
        userId: UUID,
        type: NotificationType,
        title: String,
        message: String,
        orderId: UUID? = null
    ): Notification {
        val notification = Notification(
            userId = userId,
            type = type,
            title = title,
            message = message,
            orderId = orderId
        )
        
        val savedNotification = notificationRepository.save(notification)
        
        logger.info { 
            "📝 알림 저장 완료: userId=$userId, type=$type, orderId=$orderId, " +
            "delivery=pending"
        }
        
        return savedNotification
    }
}
