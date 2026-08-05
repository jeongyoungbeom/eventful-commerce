package com.eventfulcommerce.order.dto

import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "주문 응답. 주문 성공 상품은 sellerOrders에, 재고 부족/판매 불가 상품은 failedItems에 분리되어 반환됩니다.")
data class OrderResponse(
    @field:Schema(description = "주문 ID. 모든 상품이 실패해 주문이 저장되지 않으면 null입니다.")
    val orderId: UUID?,
    @field:Schema(description = "상품 금액 합계", example = "58000")
    val totalItemAmount: Long,
    @field:Schema(description = "배송비 합계. 현재는 기본 0이며 판매자별 배송비 확장용입니다.", example = "0")
    val totalDeliveryFee: Long,
    @field:Schema(description = "사용자가 결제할 총 금액. 상품 합계 + 배송비입니다.", example = "58000")
    val totalPaymentAmount: Long,
    @field:Schema(description = "플랫폼 수수료 총액", example = "5800")
    val totalCommissionAmount: Long,
    @field:Schema(description = "판매자 정산 예정 총액", example = "52200")
    val totalSettlementAmount: Long,
    val status: OrdersStatus,
    val expiresAt: Instant?,
    val sellerOrders: List<SellerOrderResponse>,
    val failedItems: List<FailedOrderItemResponse> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(order: Orders) = OrderResponse(
            orderId = order.id,
            totalItemAmount = order.totalItemAmount,
            totalDeliveryFee = order.totalDeliveryFee,
            totalPaymentAmount = order.totalPaymentAmount,
            totalCommissionAmount = order.totalCommissionAmount,
            totalSettlementAmount = order.totalSettlementAmount,
            status = order.status,
            expiresAt = order.expiresAt,
            sellerOrders = order.sellerOrders.map { SellerOrderResponse.from(it) },
            createdAt = order.createdAt,
            updatedAt = order.updatedAt
        )
    }
}
