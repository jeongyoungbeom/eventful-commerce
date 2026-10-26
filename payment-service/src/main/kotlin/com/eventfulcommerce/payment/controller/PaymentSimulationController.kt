package com.eventfulcommerce.payment.controller

import com.eventfulcommerce.payment.domain.PaymentWebhookRequest
import com.eventfulcommerce.payment.service.PaymentWebhookService
import io.swagger.v3.oas.annotations.Operation
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

@RestController
@Profile("payment-simulation & !prod")
@ConditionalOnProperty(prefix = "payment.simulation", name = ["enabled"], havingValue = "true")
@RequestMapping("/payments/simulation")
class PaymentSimulationController(
    private val paymentWebhookService: PaymentWebhookService,
    @Value("\${payment.simulation.token:}") private val token: String
) {
    @PostMapping("/webhook")
    @Operation(summary = "로컬 결제 시뮬레이션", description = "명시적 로컬 프로필과 전용 토큰이 필요합니다. 실제 PG 인증 경로가 아닙니다.")
    fun webhook(
        @RequestBody request: PaymentWebhookRequest,
        @RequestHeader(name = "X-Payment-Simulation-Token", required = false) suppliedToken: String?
    ): ResponseEntity<String> {
        if (token.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "결제 시뮬레이션 토큰이 구성되지 않았습니다")
        }
        if (suppliedToken == null || !MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), suppliedToken.toByteArray(Charsets.UTF_8))) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "결제 시뮬레이션 인증에 실패했습니다")
        }
        paymentWebhookService.handle(request)
        return ResponseEntity.ok("Payment simulation processed")
    }
}
