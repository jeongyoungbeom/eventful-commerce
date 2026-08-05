package com.eventfulcommerce.order.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Size

@Schema(description = "다상품 주문 생성 요청. 여러 판매자의 상품이 섞이면 판매자별 SellerOrder로 자동 그룹화됩니다.")
data class OrdersRequest(
    @field:Schema(description = "주문할 상품 목록", required = true)
    @field:Size(min = 1, message = "주문 상품은 1개 이상이어야 합니다")
    @field:Valid
    val items: List<OrderItemRequest>
)
