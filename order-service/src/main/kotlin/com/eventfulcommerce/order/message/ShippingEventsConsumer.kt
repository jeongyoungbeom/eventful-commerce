package com.eventfulcommerce.order.message

import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.order.service.OrdersService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class ShippingEventsConsumer(
    private val objectMapper: ObjectMapper,
    private val ordersService: OrdersService,
    private val mixedOrdersService: com.eventfulcommerce.order.service.MixedOrdersService
) {
    @KafkaListener(topics = ["shipping-events"], groupId = "order-service-group")
    fun receive(value: String) {
        val event = objectMapper.readValue(value, OutboxEventMessage::class.java)
        when (event.eventType) {
            "SHIPPING_FAILED" -> ordersService.handleShippingFailed(event)
            "SHIPPING_CANCELLATION_DECIDED" -> ordersService.handleShippingCancellationDecided(event)
        }
        val id = objectMapper.readTree(event.payload).get("orderId")?.asText()?.let(java.util.UUID::fromString)
        if (id != null && mixedOrdersService.contains(id)) mixedOrdersService.recover(id)
    }
}
