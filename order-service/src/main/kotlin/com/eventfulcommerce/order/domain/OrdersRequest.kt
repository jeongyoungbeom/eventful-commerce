package com.eventfulcommerce.order.domain

import com.eventfulcommerce.order.domain.entity.Orders
import java.util.*

data class OrdersRequest(
    val userId: String,
    val totalAmount: Long
) {
    fun toEntity(): Orders {
        return Orders(
            userId = UUID.fromString(userId),
            totalAmount = totalAmount,
            status = OrdersStatus.ORDER_RESERVED,
        )
    }
}