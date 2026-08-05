package com.eventfulcommerce.common

import java.util.UUID

data class ProductStockUpdatedPayload(
    val productId: UUID,
    val sellerId: UUID,
    val stockDelta: Int,
    val newStock: Int,
    /**
     * True when the source was an Order-side Redis transition. Order service still refreshes
     * its read model but must not apply the same delta to Redis a second time.
     */
    val redisAlreadyAdjusted: Boolean = false
)
