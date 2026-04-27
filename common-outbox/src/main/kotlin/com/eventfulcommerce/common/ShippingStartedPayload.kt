package com.eventfulcommerce.common

import java.util.UUID

data class ShippingStartedPayload(
    val orderId: UUID,
    val userId: UUID,
    val trackingNumber: String
)
