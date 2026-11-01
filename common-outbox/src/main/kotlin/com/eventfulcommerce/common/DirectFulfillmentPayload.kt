package com.eventfulcommerce.common

import java.util.UUID

/** 직매입의 고정 품목·예약·창고 원본. 출고 지시 ID는 reservationId다. */
data class DirectFulfillmentPayload(
    val orderId: UUID,
    val userId: UUID,
    val sellerOrderId: UUID,
    val orderItemId: UUID,
    val reservationId: UUID,
    val skuId: UUID,
    val warehouseId: UUID,
    val quantity: Long,
    val schemaVersion: Int = 1
) {
    init { require(quantity > 0 && schemaVersion == 1) }
}
