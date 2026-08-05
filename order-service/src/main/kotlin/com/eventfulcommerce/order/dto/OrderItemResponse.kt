package com.eventfulcommerce.order.dto

import com.eventfulcommerce.order.domain.entity.OrderItemStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "주문 상품 상세")
data class OrderItemResponse(
    val orderItemId: UUID,
    val productId: UUID,
    val productName: String,
    val quantity: Int,
    val unitPrice: Long,
    val totalAmount: Long,
    val reservationId: UUID,
    val status: OrderItemStatus
)
