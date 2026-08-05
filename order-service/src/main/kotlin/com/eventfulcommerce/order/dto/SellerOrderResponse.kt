package com.eventfulcommerce.order.dto

import com.eventfulcommerce.order.domain.entity.SellerOrder
import com.eventfulcommerce.order.domain.entity.SellerOrderStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "판매자별 주문 그룹. 배송, 정산, 부분 취소/환불의 기준입니다.")
data class SellerOrderResponse(
    @field:Schema(description = "판매자 주문 ID")
    val sellerOrderId: UUID,
    @field:Schema(description = "판매자 ID")
    val sellerId: UUID,
    val itemTotalAmount: Long,
    val deliveryFee: Long,
    val paymentAmount: Long,
    val commissionRate: Double,
    val commissionAmount: Long,
    val settlementAmount: Long,
    val status: SellerOrderStatus,
    val items: List<OrderItemResponse>
) {
    companion object {
        fun from(sellerOrder: SellerOrder) = SellerOrderResponse(
            sellerOrderId = sellerOrder.id,
            sellerId = sellerOrder.sellerId,
            itemTotalAmount = sellerOrder.itemTotalAmount,
            deliveryFee = sellerOrder.deliveryFee,
            paymentAmount = sellerOrder.paymentAmount,
            commissionRate = sellerOrder.commissionRate,
            commissionAmount = sellerOrder.commissionAmount,
            settlementAmount = sellerOrder.settlementAmount,
            status = sellerOrder.status,
            items = sellerOrder.items.map {
                OrderItemResponse(
                    orderItemId = it.id,
                    productId = it.productId,
                    productName = it.productName,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice,
                    totalAmount = it.totalAmount,
                    reservationId = it.reservationId,
                    status = it.status
                )
            }
        )
    }
}
