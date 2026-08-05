package com.eventfulcommerce.order.dto

import com.eventfulcommerce.order.domain.OrdersStatus
import java.util.UUID

data class OrderStatusErrorDetails(
    val orderId: UUID,
    val currentStatus: OrdersStatus,
    val expectedStatus: OrdersStatus
)
