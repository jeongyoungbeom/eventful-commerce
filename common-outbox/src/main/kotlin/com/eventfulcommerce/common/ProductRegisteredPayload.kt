package com.eventfulcommerce.common

import java.util.UUID

data class ProductRegisteredPayload(
    val productId: UUID,
    val sellerId: UUID,
    val name: String,
    val price: Long,
    val initialStock: Int,
    val category: String
)
