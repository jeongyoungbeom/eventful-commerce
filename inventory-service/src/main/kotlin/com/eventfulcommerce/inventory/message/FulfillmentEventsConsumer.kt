package com.eventfulcommerce.inventory.message

import com.eventfulcommerce.common.*
import com.eventfulcommerce.inventory.service.FulfillmentService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class FulfillmentEventsConsumer(private val mapper: ObjectMapper, private val service: FulfillmentService) {
    @KafkaListener(topics = ["order-events", "shipping-events"], groupId = "inventory-fulfillment")
    fun receive(value: String) {
        val event = mapper.readValue(value, OutboxEventMessage::class.java)
        when (event.eventType) {
            "DIRECT_FULFILLMENT_REQUESTED" -> service.create(mapper.readValue(event.payload, DirectFulfillmentPayload::class.java))
            "DIRECT_SHIPPING_COMPLETED" -> service.delivered(mapper.readValue(event.payload, DirectFulfillmentPayload::class.java))
        }
    }
}
