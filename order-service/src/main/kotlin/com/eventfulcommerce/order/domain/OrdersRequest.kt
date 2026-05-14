package com.eventfulcommerce.order.domain

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.util.UUID

@Schema(description = "다상품 주문 생성 요청. 여러 판매자의 상품이 섞이면 판매자별 SellerOrder로 자동 그룹화됩니다.")
data class OrdersRequest(
    @field:Schema(description = "주문할 상품 목록", required = true)
    @field:Size(min = 1, message = "주문 상품은 1개 이상이어야 합니다")
    @field:Valid
    val items: List<OrderItemRequest>
)

@Schema(description = "주문할 단일 상품과 수량")
data class OrderItemRequest(
    @field:Schema(description = "주문할 상품 ID", example = "018f5f6e-7a9b-7c01-8f65-2baf8c1c0001", required = true)
    @field:NotNull(message = "상품 ID는 필수입니다")
    val productId: UUID,

    @field:Schema(description = "주문 수량. Redis 재고 예약도 이 수량만큼 차감됩니다.", example = "2", minimum = "1", required = true)
    @field:Positive(message = "수량은 1 이상이어야 합니다")
    val quantity: Int
)
