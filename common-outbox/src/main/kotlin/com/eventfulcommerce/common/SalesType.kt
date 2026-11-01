package com.eventfulcommerce.common

import java.util.UUID

enum class InventorySource { SELLER_REDIS, INVENTORY_SERVICE }

enum class SalesType(val inventorySource: InventorySource) {
    MARKETPLACE(InventorySource.SELLER_REDIS),
    DIRECT_PURCHASE(InventorySource.INVENTORY_SERVICE);

    fun validateSeller(sellerId: UUID) {
        require((sellerId == OFFICIAL_SELLER_ID) == (this == DIRECT_PURCHASE)) {
            "판매 주체와 판매 방식이 일치하지 않습니다"
        }
    }

    companion object {
        // 로그인 계정으로 발급하지 않는 플랫폼 판매 주체의 예약 ID다.
        val OFFICIAL_SELLER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
