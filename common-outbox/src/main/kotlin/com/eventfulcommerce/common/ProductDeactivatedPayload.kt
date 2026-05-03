package com.eventfulcommerce.common

import java.util.UUID

data class ProductDeactivatedPayload(
    val productId: UUID,
    val sellerId: UUID
)
