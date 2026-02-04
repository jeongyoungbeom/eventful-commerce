package com.eventfulcommerce.order.service

import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.repository.OrdersRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class OrdersService(
    private val ordersRepository: OrdersRepository,
    private val outboxEventService: OutboxEventService
) {
    @Transactional
    fun orders(ordersRequests: List<OrdersRequest>): String {
        val orderList = ordersRequests.map { it.toEntity(it) }
        val saveOrders = ordersRepository.saveAll(orderList)
        outboxEventService.recode(saveOrders)

        return "success"
    }
}