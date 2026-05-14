package com.eventfulcommerce.common

import java.time.Instant
import java.util.UUID

data class OrderConfirmedPayload(
    val orderId: UUID,
    val userId: UUID,
    val totalAmount: Long,
    val sellerOrders: List<OrderConfirmedSellerPayload> = emptyList(),
    val confirmedAt: Instant,
)

data class OrderConfirmedSellerPayload(
    val sellerOrderId: UUID,
    val sellerId: UUID,
    val paymentAmount: Long
)
