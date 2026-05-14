package com.eventfulcommerce.common

import java.time.Instant
import java.util.UUID

data class OrderReservedPayload(
    val orderId: UUID,
    val userId: UUID,
    val totalItemAmount: Long,
    val totalDeliveryFee: Long,
    val totalPaymentAmount: Long,
    val totalCommissionAmount: Long,
    val totalSettlementAmount: Long,
    val sellerOrders: List<OrderReservedSellerPayload>,
    val expiresAt: Instant?,
    val createdAt: Instant
)

data class OrderReservedSellerPayload(
    val sellerOrderId: UUID,
    val sellerId: UUID,
    val itemTotalAmount: Long,
    val deliveryFee: Long,
    val paymentAmount: Long,
    val commissionRate: Double,
    val commissionAmount: Long,
    val settlementAmount: Long,
    val items: List<OrderReservedItemPayload>
)

data class OrderReservedItemPayload(
    val orderItemId: UUID,
    val productId: UUID,
    val productName: String,
    val quantity: Int,
    val unitPrice: Long,
    val totalAmount: Long,
    val reservationId: UUID
)
