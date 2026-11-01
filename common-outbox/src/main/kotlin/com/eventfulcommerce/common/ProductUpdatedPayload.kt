package com.eventfulcommerce.common

import java.util.UUID

data class ProductUpdatedPayload(
    val productId: UUID,
    val sellerId: UUID,
    val name: String,
    val price: Long,
    val category: String,
    val informationVersion: Long,
    val salesType: SalesType = SalesType.MARKETPLACE,
    val skuId: UUID = productId,
    val schemaVersion: Int = 1
) {
    init {
        require(schemaVersion in 1..2) { "Unsupported product schema version" }
        require(skuId == productId) { "상품과 SKU는 1:1이어야 합니다" }
        salesType.validateSeller(sellerId)
        require(schemaVersion == 2 || salesType == SalesType.MARKETPLACE) { "직매입은 v2 계약이 필요합니다" }
    }
}
