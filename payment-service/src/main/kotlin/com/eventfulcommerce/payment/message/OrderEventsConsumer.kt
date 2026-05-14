package com.eventfulcommerce.payment.message

import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.payment.service.PaymentService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class OrderEventsConsumer(
    private val objectMapper: ObjectMapper,
    private val paymentService: PaymentService
) {

    @KafkaListener(topics = ["order-events"], groupId = "payment-service")
    fun receive(value: String) {
        val readValue = objectMapper.readValue(value, OutboxEventMessage::class.java)

        when (readValue.eventType) {
            "ORDER_RESERVED" -> paymentService.handleOrderCreated(readValue)
            "ORDER_CANCELED" -> paymentService.handleOrderCanceled(readValue)
        }
    }
}
