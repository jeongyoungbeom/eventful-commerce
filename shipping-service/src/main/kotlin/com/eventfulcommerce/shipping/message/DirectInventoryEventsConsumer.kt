package com.eventfulcommerce.shipping.message

import com.eventfulcommerce.common.*
import com.eventfulcommerce.shipping.service.DirectShippingService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class DirectInventoryEventsConsumer(private val mapper: ObjectMapper, private val service: DirectShippingService) {
    @KafkaListener(topics = ["direct-inventory-events"], groupId = "shipping-direct-outbound")
    fun receive(value: String) {
        val event = mapper.readValue(value, OutboxEventMessage::class.java)
        if (event.eventType == "OUTBOUND_SHIPPED") service.shipped(mapper.readValue(event.payload, DirectFulfillmentPayload::class.java))
    }
}
