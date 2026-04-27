package com.eventfulcommerce.common

import java.util.UUID

data class OrderCanceledPayload(
    val orderId: UUID,
    val userId: UUID,
    val reason: String
)
