package com.eventfulcommerce.notification.message

import com.eventfulcommerce.common.OrderCanceledPayload
import com.eventfulcommerce.common.OrderReservedPayload
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.notification.domain.NotificationType
import com.eventfulcommerce.notification.service.NotificationService
import com.eventfulcommerce.notification.service.NotificationTemplate
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class OrderEventsConsumer(
    private val objectMapper: ObjectMapper,
    private val notificationService: NotificationService
) {

    @KafkaListener(topics = ["order-events"], groupId = "notification-service-group")
    fun consume(value: String) {
        logger.info { "📨 Order 이벤트 수신: $value" }
        
        try {
            val eventMessage = objectMapper.readValue(value, OutboxEventMessage::class.java)
            
            when (eventMessage.eventType) {
                "ORDER_RESERVED" -> handleOrderReserved(eventMessage)
                "ORDER_CANCELED" -> handleOrderCanceled(eventMessage)
                else -> logger.warn { "⚠️ 알 수 없는 이벤트 타입: ${eventMessage.eventType}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Order 이벤트 처리 실패: $value" }
            throw e
        }
    }

    private fun handleOrderReserved(eventMessage: OutboxEventMessage) {
        val payload = objectMapper.readValue(eventMessage.payload, OrderReservedPayload::class.java)
        
        val (title, message) = NotificationTemplate.orderReserved(payload.orderId)
        
        notificationService.createAndSend(
            userId = payload.userId,
            type = NotificationType.ORDER_RESERVED,
            title = title,
            message = message,
            orderId = payload.orderId
        )
        
        logger.info { "✅ ORDER_RESERVED 알림 처리 완료: orderId=${payload.orderId}" }
    }

    private fun handleOrderCanceled(eventMessage: OutboxEventMessage) {
        val payload = objectMapper.readValue(eventMessage.payload, OrderCanceledPayload::class.java)
        
        val (title, message) = NotificationTemplate.orderCanceled(payload.orderId, payload.reason)
        
        notificationService.createAndSend(
            userId = payload.userId,
            type = NotificationType.ORDER_CANCELED,
            title = title,
            message = message,
            orderId = payload.orderId
        )
        
        logger.info { "✅ ORDER_CANCELED 알림 처리 완료: orderId=${payload.orderId}" }
    }
}
