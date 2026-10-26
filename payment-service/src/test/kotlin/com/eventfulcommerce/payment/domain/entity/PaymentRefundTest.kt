package com.eventfulcommerce.payment.domain.entity

import com.eventfulcommerce.payment.domain.PaymentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PaymentRefundTest {
    @Test
    fun `나노초 환불 시각은 생성 시 저장 가능한 마이크로초로 절삭한다`() {
        val payment = Payment(UUID.randomUUID(), UUID.randomUUID(),
            PaymentStatus.PAYMENT_COMPLETED, 20_000, "[]")
        listOf(
            "2026-10-07T12:34:56.123456789Z" to "2026-10-07T12:34:56.123456Z",
            "2026-10-07T12:34:56.999999999Z" to "2026-10-07T12:34:56.999999Z",
            "2026-10-07T12:34:56.123456Z" to "2026-10-07T12:34:56.123456Z"
        ).forEach { (input, expected) ->
            val refund = PaymentRefund(payment, payment.orderId, UUID.randomUUID(),
                UUID.randomUUID(), 5_000, "부분 취소", Instant.parse(input))
            assertEquals(Instant.parse(expected), refund.refundedAt)
        }
    }
}
