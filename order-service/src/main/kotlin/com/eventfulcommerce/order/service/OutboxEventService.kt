package com.eventfulcommerce.order.service

import com.eventfulcommerce.order.domain.OrderCreatedPayload
import com.eventfulcommerce.order.domain.OutboxStatus
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.domain.entity.OutboxEvent
import com.eventfulcommerce.order.repository.OutboxEventRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.util.*

@Component
class OutboxEventService(
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper
) {
//    fun recode(aggregateType: String, aggregateId: UUID, eventType: String, payload: Any): UUID {
//        val eventId = UUID.randomUUID()
//        val payloadJson = objectMapper.writeValueAsString(payload)
//
//        outboxEventRepository.save(OutboxEvent(
//            id = eventId,
//            aggregateType = aggregateType,
//            aggregateId = aggregateId,
//            eventType = eventType,
//            payload = payloadJson,
//            status = OutboxStatus.PENDING
//            )
//        )
//
//        return eventId
//    }

    fun recode(orders: List<Orders>) {
        val events = orders.map {
            val payloadJson = objectMapper.writeValueAsString(
                OrderCreatedPayload(
                    orderId = it.id!!,
                    userId = it.userId,
                    totalAmount = it.totalAmount,
                    createdAt = it.createdAt,
                )
            )

            OutboxEvent(
                aggregateType = "ORDER",
                aggregateId = it.id,
                eventType = "ORDER_CREATED",
                payload = payloadJson,
                status = OutboxStatus.PENDING
            )
        }

        outboxEventRepository.saveAll(events)
    }
}