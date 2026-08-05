package com.eventfulcommerce.order.dto

import java.util.UUID

data class LockTestResponse(
    val success: Boolean,
    val orderId: UUID,
    val holdTime: Long,
    val message: String
)
