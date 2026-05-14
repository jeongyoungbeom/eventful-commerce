package com.eventfulcommerce.common

import java.time.Instant
import java.util.UUID

data class PaymentRefundedPayload(
    val refundId: UUID,
    val paymentId: UUID,
    val orderId: UUID,
    val sellerOrderId: UUID,
    val sellerId: UUID,
    val amount: Long,
    val reason: String,
    val refundedAt: Instant
)
