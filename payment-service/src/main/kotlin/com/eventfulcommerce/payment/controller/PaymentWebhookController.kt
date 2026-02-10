package com.eventfulcommerce.payment.controller

import com.eventfulcommerce.payment.domain.PaymentWebhookRequest
import com.eventfulcommerce.payment.service.PaymentWebhookService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/payments")
class PaymentWebhookController(
    private val paymentWebhookService: PaymentWebhookService
) {
    @PostMapping("/webhook")
    fun webhook(@RequestBody request: PaymentWebhookRequest): ResponseEntity<String> {
        paymentWebhookService.handle(request)
        return ResponseEntity("Payment successful", HttpStatus.OK)
    }
}