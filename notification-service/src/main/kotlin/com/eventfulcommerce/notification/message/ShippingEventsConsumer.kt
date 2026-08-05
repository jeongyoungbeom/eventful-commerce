package com.eventfulcommerce.notification.message

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.ShippingCompletedPayload
import com.eventfulcommerce.common.ShippingStartedPayload
import com.eventfulcommerce.notification.domain.NotificationType
import com.eventfulcommerce.notification.service.NotificationService
import com.eventfulcommerce.notification.service.NotificationTemplate
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}
private val supportedShippingNotificationEvents = setOf("SHIPPING_STARTED", "SHIPPING_COMPLETED")

@Component
class ShippingEventsConsumer(
    private val objectMapper: ObjectMapper,
    private val notificationService: NotificationService,
    private val idempotencyHandler: IdempotencyHandler
) {

    @KafkaListener(topics = ["shipping-events"], groupId = "notification-service-group")
    fun consume(value: String) {
        logger.info { "📨 Shipping 이벤트 수신: $value" }
        
        try {
            val eventMessage = objectMapper.readValue(value, OutboxEventMessage::class.java)

            if (eventMessage.eventType !in supportedShippingNotificationEvents) {
                logger.warn { "Unsupported shipping notification event: ${eventMessage.eventType}" }
                return
            }

            idempotencyHandler.executeIdempotent(eventMessage.eventId) {
                when (eventMessage.eventType) {
                    "SHIPPING_STARTED" -> handleShippingStarted(eventMessage)
                    "SHIPPING_COMPLETED" -> handleShippingCompleted(eventMessage)
                    else -> logger.warn { "⚠️ 알 수 없는 이벤트 타입: ${eventMessage.eventType}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Shipping 이벤트 처리 실패: $value" }
            throw e
        }
    }

    private fun handleShippingStarted(eventMessage: OutboxEventMessage) {
        val payload = objectMapper.readValue(eventMessage.payload, ShippingStartedPayload::class.java)
        
        val (title, message) = NotificationTemplate.shippingStarted(
            payload.orderId,
            payload.trackingNumber
        )
        
        notificationService.create(
            userId = payload.userId,
            type = NotificationType.SHIPPING_STARTED,
            title = title,
            message = message,
            orderId = payload.orderId
        )
        
        logger.info { "✅ SHIPPING_STARTED 알림 처리 완료: orderId=${payload.orderId}" }
    }

    private fun handleShippingCompleted(eventMessage: OutboxEventMessage) {
        val payload = objectMapper.readValue(eventMessage.payload, ShippingCompletedPayload::class.java)
        
        val (title, message) = NotificationTemplate.shippingCompleted(payload.orderId)
        
        notificationService.create(
            userId = payload.userId,
            type = NotificationType.SHIPPING_COMPLETED,
            title = title,
            message = message,
            orderId = payload.orderId
        )
        
        logger.info { "✅ SHIPPING_COMPLETED 알림 처리 완료: orderId=${payload.orderId}" }
    }
}
