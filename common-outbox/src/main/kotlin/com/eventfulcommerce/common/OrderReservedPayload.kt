package com.eventfulcommerce.common

import java.time.Instant
import java.util.UUID

data class OrderReservedPayload(
    val orderId: UUID,
    val userId: UUID,
    val productId: UUID,
    val sellerId: UUID,
    val reservationId: UUID,
    val totalAmount: Long,
    val quantity: Int,
    val expiresAt: Instant?,
    val createdAt: Instant
)
