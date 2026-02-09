package com.eventfulcommerce.common

import java.time.Instant
import java.util.UUID

data class OrderCreatedPayload(
    val orderId: UUID,
    val userId: UUID,
    val totalAmount: Long,
    val createdAt: Instant
)