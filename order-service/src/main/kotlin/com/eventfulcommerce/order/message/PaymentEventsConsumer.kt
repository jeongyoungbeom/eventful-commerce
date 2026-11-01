package com.eventfulcommerce.order.message

import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.order.service.OrdersService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class PaymentEventsConsumer(
    private val objectMapper: ObjectMapper,
    private val ordersService: OrdersService,
    private val mixedOrdersService: com.eventfulcommerce.order.service.MixedOrdersService
) {
    @KafkaListener(topics = ["payment-events"], groupId = "order-service-group")
    fun receive(value: String) {
        val readValue = objectMapper.readValue(value, OutboxEventMessage::class.java)
        val orderId = objectMapper.readTree(readValue.payload).get("orderId")?.asText()?.let(java.util.UUID::fromString)
        if (orderId != null && mixedOrdersService.contains(orderId)) {
            when (readValue.eventType) {
                "PAYMENT_COMPLETED" -> mixedOrdersService.paymentCompleted(objectMapper.readValue(readValue.payload, com.eventfulcommerce.common.PaymentCompletedPayload::class.java))
                "PAYMENT_FAILED" -> mixedOrdersService.paymentFailed(objectMapper.readValue(readValue.payload, com.eventfulcommerce.common.PaymentFailedPayload::class.java))
                "PAYMENT_REFUNDED" -> mixedOrdersService.refundReceived(objectMapper.readValue(readValue.payload, com.eventfulcommerce.common.PaymentRefundedPayload::class.java))
            }
            return
        }
        when (readValue.eventType) {
            "PAYMENT_COMPLETED" -> ordersService.handlePaymentCompleted(readValue)
            "PAYMENT_FAILED" -> ordersService.handlePaymentFailed(readValue)
            "PAYMENT_REFUNDED" -> ordersService.handlePaymentRefunded(readValue)
        }
    }
}
