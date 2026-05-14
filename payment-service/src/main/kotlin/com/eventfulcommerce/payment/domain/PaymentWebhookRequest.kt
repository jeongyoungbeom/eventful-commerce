package com.eventfulcommerce.payment.domain

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "PG 결제 결과 웹훅 요청")
data class PaymentWebhookRequest(
    @field:Schema(description = "결제 대상 주문 ID", required = true)
    val orderId: UUID,
    @field:Schema(description = "결제 결과. SUCCESS면 결제 완료, 그 외 값은 결제 실패로 처리합니다.", example = "SUCCESS", required = true)
    val result: String,
    @field:Schema(description = "PG 거래 ID", example = "PG-20260514-0001")
    val pgTxId: String? = null,
    @field:Schema(description = "PG에서 전달한 결제 금액. 현재 검증용 보조 값입니다.", example = "58000")
    val amount: Long? = null,
)
