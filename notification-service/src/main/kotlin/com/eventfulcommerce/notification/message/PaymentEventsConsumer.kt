package com.eventfulcommerce.notification.message

import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.notification.domain.NotificationType
import com.eventfulcommerce.notification.service.NotificationService
import com.eventfulcommerce.notification.service.NotificationTemplate
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class PaymentEventsConsumer(
    private val objectMapper: ObjectMapper,
    private val notificationService: NotificationService
) {

    @KafkaListener(topics = ["payment-events"], groupId = "notification-service-group")
    fun consume(value: String) {
        logger.info { "📨 Payment 이벤트 수신: $value" }
        
        try {
            val eventMessage = objectMapper.readValue(value, OutboxEventMessage::class.java)
            
            when (eventMessage.eventType) {
                "PAYMENT_COMPLETED" -> handlePaymentCompleted(eventMessage)
                else -> logger.warn { "⚠️ 알 수 없는 이벤트 타입: ${eventMessage.eventType}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Payment 이벤트 처리 실패: $value" }
            throw e
        }
    }

    private fun handlePaymentCompleted(eventMessage: OutboxEventMessage) {
        val payload = objectMapper.readValue(eventMessage.payload, PaymentCompletedPayload::class.java)

        // 구매자 알림
        val (buyerTitle, buyerMessage) = NotificationTemplate.paymentCompleted(payload.orderId, payload.amount)
        notificationService.createAndSend(
            userId = payload.userId,
            type = NotificationType.PAYMENT_COMPLETED,
            title = buyerTitle,
            message = buyerMessage,
            orderId = payload.orderId
        )

        payload.sellerOrders.forEach { sellerOrder ->
            val (sellerTitle, sellerMessage) = NotificationTemplate.sellerPaymentReceived(payload.orderId, sellerOrder.paymentAmount)
            notificationService.createAndSend(
                userId = sellerOrder.sellerId,
                type = NotificationType.PAYMENT_COMPLETED,
                title = sellerTitle,
                message = sellerMessage,
                orderId = payload.orderId
            )
        }

        logger.info { "✅ PAYMENT_COMPLETED 알림 처리 완료: orderId=${payload.orderId}" }
    }
}
