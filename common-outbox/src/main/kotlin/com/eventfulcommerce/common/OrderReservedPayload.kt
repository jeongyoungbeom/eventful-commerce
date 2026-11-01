package com.eventfulcommerce.common

import java.time.Instant
import java.util.UUID

data class OrderReservedPayload(
    val orderId: UUID,
    val userId: UUID,
    val totalItemAmount: Long,
    val totalDeliveryFee: Long,
    val totalPaymentAmount: Long,
    val totalCommissionAmount: Long,
    val totalSettlementAmount: Long,
    val sellerOrders: List<OrderReservedSellerPayload>,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val compositionVersion: Long? = null,
    val confirmedVersion: Long? = null
)

data class OrderReservedSellerPayload(
    val sellerOrderId: UUID,
    val sellerId: UUID,
    val itemTotalAmount: Long,
    val deliveryFee: Long,
    val paymentAmount: Long,
    val commissionRate: Double,
    val commissionAmount: Long,
    val settlementAmount: Long,
    val items: List<OrderReservedItemPayload>,
    val salesType: SalesType = SalesType.MARKETPLACE
) {
    init { salesType.validateSeller(sellerId) }
}

data class OrderReservedItemPayload(
    val orderItemId: UUID,
    val productId: UUID,
    val productName: String,
    val quantity: Int,
    val unitPrice: Long,
    val totalAmount: Long,
    val reservationId: UUID,
    val skuId: UUID = productId,
    val deliveryFeeAllocation: Long = 0
) {
    init {
        require(skuId == productId) { "상품과 SKU는 1:1이어야 합니다" }
        require(deliveryFeeAllocation >= 0) { "배송비 배분은 음수일 수 없습니다" }
    }
}
