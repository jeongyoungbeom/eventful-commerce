package com.eventfulcommerce.shipping.messaging

import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.shipping.service.ShippingService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class PaymentEventsConsumer(
    private val objectMapper: ObjectMapper,
    private val shippingService: ShippingService
) {

    @KafkaListener(topics = ["payment-events"], groupId = "shipping-service")
    fun receive(value: String) {
        val readValue = objectMapper.readValue(value, OutboxEventMessage::class.java)
        shippingService.handlePaymentCompleted(readValue.eventId, readValue.payload)
    }
}