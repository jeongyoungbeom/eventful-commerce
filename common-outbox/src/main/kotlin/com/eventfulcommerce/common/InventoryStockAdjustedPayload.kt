package com.eventfulcommerce.common

import java.util.UUID

/**
 * Order service has already applied this change to Redis. Product service consumes the fact
 * to keep its database stock projection aligned, then emits a Product event that must not
 * be applied to Redis again.
 */
data class InventoryStockAdjustedPayload(
    val orderId: UUID,
    val productId: UUID,
    val reservationId: UUID,
    val stockDelta: Int,
    val reason: String
)
