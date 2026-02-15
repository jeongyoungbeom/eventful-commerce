package com.eventfulcommerce.shipping.message

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

    @KafkaListener(topics = ["order-events"], groupId = "shipping-service")
    fun receive(value: String) {
        val readValue = objectMapper.readValue(value, OutboxEventMessage::class.java)
        if (readValue.eventType != "ORDER_CONFIRMED") return

        shippingService.handleOrderConfirmed(readValue.eventId, readValue.payload)
    }
}