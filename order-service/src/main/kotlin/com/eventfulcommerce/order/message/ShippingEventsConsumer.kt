package com.eventfulcommerce.order.message

import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.order.service.OrdersService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class ShippingEventsConsumer(
    private val objectMapper: ObjectMapper,
    private val ordersService: OrdersService
) {
    @KafkaListener(topics = ["shipping-events"], groupId = "order-service-group")
    fun receive(value: String) {
        val event = objectMapper.readValue(value, OutboxEventMessage::class.java)
        if (event.eventType == "SHIPPING_FAILED") ordersService.handleShippingFailed(event)
    }
}
