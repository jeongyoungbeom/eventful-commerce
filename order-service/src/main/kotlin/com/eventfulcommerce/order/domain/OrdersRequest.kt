package com.eventfulcommerce.order.domain

import com.eventfulcommerce.order.domain.entity.Orders
import java.util.UUID

data class OrdersRequest(
    val productId: UUID,
    val quantity: Int
) {
    fun toEntity(userId: UUID, sellerId: UUID, price: Long): Orders {
        return Orders(
            userId = userId,
            productId = productId,
            sellerId = sellerId,
            quantity = quantity,
            totalAmount = price * quantity,
            status = OrdersStatus.ORDER_RESERVED,
        )
    }
}
