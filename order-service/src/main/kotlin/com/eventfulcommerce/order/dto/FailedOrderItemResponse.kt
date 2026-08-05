package com.eventfulcommerce.order.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "주문에서 제외된 실패 상품")
data class FailedOrderItemResponse(
    @field:Schema(description = "실패한 상품 ID")
    val productId: UUID,
    @field:Schema(description = "실패 사유. INSUFFICIENT_STOCK 또는 PRODUCT_NOT_AVAILABLE", example = "INSUFFICIENT_STOCK")
    val reason: String,
    @field:Schema(description = "요청 수량", example = "3")
    val requestedQuantity: Int,
    @field:Schema(description = "현재 주문 가능한 재고 수량", example = "1")
    val availableQuantity: Long
)
