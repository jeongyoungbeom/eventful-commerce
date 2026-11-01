package com.eventfulcommerce.common

import java.util.UUID

data class OrderCanceledPayload(
    val orderId: UUID,
    val userId: UUID,
    val reason: String,
    val canceledSellerOrders: List<OrderCanceledSellerPayload> = emptyList()
)

data class OrderCanceledSellerPayload(
    val sellerOrderId: UUID,
    val sellerId: UUID,
    val refundAmount: Long,
    val items: List<OrderCanceledItemPayload> = emptyList(),
    val salesType: SalesType = SalesType.MARKETPLACE,
    val orderItemId: UUID? = null
) {
    init { salesType.validateSeller(sellerId) }
}

data class OrderCanceledItemPayload(
    val orderItemId: UUID,
    val productId: UUID,
    val quantity: Int,
    val amount: Long
)
