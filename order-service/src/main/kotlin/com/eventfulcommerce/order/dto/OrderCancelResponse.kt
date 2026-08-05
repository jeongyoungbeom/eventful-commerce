package com.eventfulcommerce.order.dto

import java.util.UUID

data class OrderCancelResponse(
    val success: Boolean,
    val orderId: UUID,
    val message: String
)
