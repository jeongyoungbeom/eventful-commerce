package com.eventfulcommerce.order.dto

import java.util.UUID

data class SellerOrderCancelResponse(
    val success: Boolean,
    val orderId: UUID,
    val sellerOrderId: UUID,
    val message: String
)
