package com.eventfulcommerce.common

import java.time.Instant
import java.util.UUID

data class PaymentCompletedPayload(
    val paymentId: UUID,
    val orderId: UUID,
    val userId: UUID,
    val amount: Long,
    val sellerOrders: List<PaymentCompletedSellerPayload> = emptyList(),
    val completedAt: Instant
)

data class PaymentCompletedSellerPayload(
    val sellerOrderId: UUID,
    val sellerId: UUID,
    val paymentAmount: Long,
    val commissionRate: Double,
    val commissionAmount: Long,
    val settlementAmount: Long
)
