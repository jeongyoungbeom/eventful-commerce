package com.eventfulcommerce.common

import java.util.UUID

/**
 * A safe absolute repair fact. Order service produces it only when Redis has no active holds.
 */
data class InventoryStockSnapshotPayload(
    val productId: UUID,
    val availableStock: Int
)
