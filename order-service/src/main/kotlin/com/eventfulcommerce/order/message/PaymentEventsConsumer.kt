package com.eventfulcommerce.order.message

import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.order.service.OrdersService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class PaymentEventsConsumer(
    private val objectMapper: ObjectMapper,
    private val ordersService: OrdersService
) {
    @KafkaListener(topics = ["payment-events"], groupId = "order-service")
    fun receive(value: String) {
        val readValue = objectMapper.readValue(value, OutboxEventMessage::class.java)
        if (readValue.eventType == "PAYMENT_COMPLETED") {
            ordersService.handlePaymentCompleted(readValue)
        } else if (readValue.eventType == "PAYMENT_FAILED") {
            ordersService.handlePaymentFailed(readValue)
        }
    }
}