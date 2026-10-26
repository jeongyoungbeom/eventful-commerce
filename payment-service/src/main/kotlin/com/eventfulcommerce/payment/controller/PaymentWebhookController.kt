package com.eventfulcommerce.payment.controller

import com.eventfulcommerce.payment.service.PaymentWebhookService
import com.eventfulcommerce.payment.webhook.PaymentWebhookVerifier
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/payments")
@Tag(name = "Payments", description = "검증된 PG 결제 결과 수신 API")
class PaymentWebhookController(
    private val paymentWebhookService: PaymentWebhookService,
    private val verifier: ObjectProvider<PaymentWebhookVerifier>
) {
    @PostMapping("/webhook", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "PG 결제 결과 웹훅", description = "PG별 발신자·결제 결과 검증 후 처리합니다. 검증자가 구성되지 않으면 503으로 차단합니다.")
    fun webhook(@RequestBody rawBody: ByteArray, @RequestHeader headers: HttpHeaders): ResponseEntity<String> {
        val configuredVerifier = verifier.ifAvailable
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "PG 웹훅 검증자가 구성되지 않았습니다")
        val verified = configuredVerifier.verify(rawBody, headers)
        paymentWebhookService.handle(verified)
        return ResponseEntity.ok("Payment webhook processed")
    }
}
