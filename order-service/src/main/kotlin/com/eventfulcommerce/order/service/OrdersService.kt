package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.OrderCreatedPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.collections.map

@Service
class OrdersService(
    private val ordersRepository: OrdersRepository,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun orders(ordersRequests: List<OrdersRequest>): String {
        val orderList = ordersRequests.map { it.toEntity() }
        val saveOrders = ordersRepository.saveAll(orderList)

        val events = saveOrders.map {
            val payloadJson = objectMapper.writeValueAsString(
                OrderCreatedPayload(
                    orderId = it.id,
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
        outboxEventService.recode(events)

        return "success"
    }
}