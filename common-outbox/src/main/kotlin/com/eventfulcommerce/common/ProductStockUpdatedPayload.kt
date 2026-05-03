package com.eventfulcommerce.common

import java.util.UUID

data class ProductStockUpdatedPayload(
    val productId: UUID,
    val sellerId: UUID,
    val stockDelta: Int,
    val newStock: Int
)
