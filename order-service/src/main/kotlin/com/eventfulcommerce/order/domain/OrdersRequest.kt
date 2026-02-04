package com.eventfulcommerce.order.domain

import com.eventfulcommerce.order.domain.entity.Orders
import java.util.*

data class OrdersRequest(
    val userId: String,
    val totalAmount: Long
) {
    fun toEntity(ordersRequest: OrdersRequest): Orders {
        return Orders(
            userId = UUID.fromString(ordersRequest.userId),
            totalAmount = ordersRequest.totalAmount,
            status = OrdersStatus.ORDER_CREATED,
        )
    }
}