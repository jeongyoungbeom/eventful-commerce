package com.eventfulcommerce.common

import java.util.UUID

data class ShippingCancellationRequestedPayload(
    val requestId: UUID,
    val orderId: UUID,
    val sellerOrderId: UUID,
    val userId: UUID,
    val reason: String
)

data class ShippingCancellationDecidedPayload(
    val requestId: UUID,
    val orderId: UUID,
    val sellerOrderId: UUID,
    val userId: UUID,
    val approved: Boolean
)
