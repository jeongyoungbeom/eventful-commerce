package com.eventfulcommerce.common

import java.time.Instant
import java.util.UUID

data class OrderConfirmedPayload(
    val orderId: UUID,
    val userId: UUID,
    val totalAmount: Long,
    val confirmedAt: Instant,
)