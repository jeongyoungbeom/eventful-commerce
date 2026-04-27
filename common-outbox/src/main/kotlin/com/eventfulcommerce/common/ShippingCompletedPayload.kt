package com.eventfulcommerce.common

import java.util.UUID

data class ShippingCompletedPayload(
    val orderId: UUID,
    val userId: UUID
)
