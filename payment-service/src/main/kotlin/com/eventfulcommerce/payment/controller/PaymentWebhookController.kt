package com.eventfulcommerce.payment.controller

import com.eventfulcommerce.payment.domain.PaymentWebhookRequest
import com.eventfulcommerce.payment.service.PaymentWebhookService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/payments")
@Tag(name = "Payments", description = "결제 웹훅 수신 API")
class PaymentWebhookController(
    private val paymentWebhookService: PaymentWebhookService
) {
    @PostMapping("/webhook")
    @Operation(summary = "결제 결과 웹훅", description = "PG 결제 결과를 수신합니다. SUCCESS면 PAYMENT_COMPLETED 이벤트를 발행하고 주문 확정/배송/정산으로 이어집니다. 실패 결과면 PAYMENT_FAILED 이벤트를 발행해 예약 재고를 해제하고 주문을 취소합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "웹훅 처리 완료"),
        ApiResponse(responseCode = "400", description = "결제 정보 없음 또는 이미 처리된 상태")
    )
    fun webhook(@RequestBody request: PaymentWebhookRequest): ResponseEntity<String> {
        paymentWebhookService.handle(request)
        return ResponseEntity("Payment successful", HttpStatus.OK)
    }
}
