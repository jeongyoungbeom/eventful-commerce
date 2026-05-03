package com.eventfulcommerce.common

import java.time.Instant
import java.util.UUID

data class PaymentCompletedPayload(
    val paymentId: UUID,
    val reservationId: UUID? = null,
    val orderId: UUID,
    val userId: UUID,
    val sellerId: UUID,
    val amount: Long,
    val completedAt: Instant
)
