package com.eventfulcommerce.order.message

import com.eventfulcommerce.common.*
import com.eventfulcommerce.order.service.MixedOrderStateService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class DirectInventoryEventsConsumer(private val mapper: ObjectMapper, private val state: MixedOrderStateService) {
    @KafkaListener(topics = ["direct-inventory-events"], groupId = "order-direct-returns")
    fun receive(value: String) {
        val event = mapper.readValue(value, OutboxEventMessage::class.java)
        if (event.eventType == "DIRECT_RETURN_INSPECTED") state.returnInspected(mapper.readValue(event.payload, DirectFulfillmentPayload::class.java))
        if (event.eventType == "OUTBOUND_INELIGIBILITY_CANCELED") state.ineligibleCanceled(mapper.readValue(event.payload, DirectFulfillmentPayload::class.java))
    }
}
