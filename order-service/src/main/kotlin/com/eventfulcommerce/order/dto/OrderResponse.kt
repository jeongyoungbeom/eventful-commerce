package com.eventfulcommerce.order.dto

import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "주문 응답. 주문 성공 상품은 sellerOrders에, 재고 부족/판매 불가 상품은 failedItems에 분리되어 반환됩니다.")
data class OrderResponse(
    @field:Schema(description = "주문 ID. 입점 단독 전부 실패는 null이며 혼합 주문은 실패해도 영속 시도 ID를 반환합니다.")
    val orderId: UUID?,
    @field:Schema(description = "상품 금액 합계", example = "58000")
    val totalItemAmount: Long,
    @field:Schema(description = "배송비 합계. 직매입은 서버 정책에 따라 주문당 한 번 계산합니다.", example = "0")
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
    val updatedAt: Instant,
    val compositionVersion: Long? = null,
    val confirmationRequired: Boolean = false,
    val confirmedVersion: Long? = null,
    val pendingItems: List<FailedOrderItemResponse> = emptyList(),
    val paymentReady: Boolean = status == OrdersStatus.ORDER_RESERVED && expiresAt?.isAfter(Instant.now()) == true
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
