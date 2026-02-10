package com.eventfulcommerce.common

import java.time.Instant
import java.util.*

data class PaymentFailedPayload(
    val paymentId: UUID,
    val orderId: UUID,
    val amount: Long,
    val reservationId: UUID,
    val failedAt: Instant,
    val pgTxId: String
)