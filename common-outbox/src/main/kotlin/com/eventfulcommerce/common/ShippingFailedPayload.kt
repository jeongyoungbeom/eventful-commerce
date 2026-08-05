package com.eventfulcommerce.common

import java.time.Instant
import java.util.UUID

data class ShippingFailedPayload(
    val orderId: UUID,
    val sellerOrderId: UUID,
    val userId: UUID,
    val reason: String,
    val failedAt: Instant
)
