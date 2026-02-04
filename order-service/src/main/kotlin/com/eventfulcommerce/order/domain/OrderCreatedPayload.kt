package com.eventfulcommerce.order.domain

import java.time.Instant
import java.util.*

data class OrderCreatedPayload(
    val orderId: UUID,
    val userId: UUID,
    val totalAmount: Long,
    val createdAt: Instant
)