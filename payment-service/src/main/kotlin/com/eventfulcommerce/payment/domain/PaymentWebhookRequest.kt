package com.eventfulcommerce.payment.domain

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "PG 결제 결과 웹훅 요청")
data class PaymentWebhookRequest(
    @field:Schema(description = "결제 대상 주문 ID", required = true)
    val orderId: UUID,
    @field:Schema(description = "결제 결과. SUCCESS 또는 FAILED만 허용합니다.", example = "SUCCESS", required = true)
    val result: String,
    @field:Schema(description = "PG 거래 ID. 필수이며 다른 결제에 재사용할 수 없습니다.", example = "PG-20260514-0001", required = true)
    val pgTxId: String? = null,
    @field:Schema(description = "결제 금액. 필수이며 예약 금액과 같아야 합니다.", example = "58000", required = true)
    val amount: Long? = null,
)
