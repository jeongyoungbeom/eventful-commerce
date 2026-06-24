package com.eventfulcommerce.settlement.message

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.IdempotencyResult
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.PaymentCompletedSellerPayload
import com.eventfulcommerce.common.PaymentRefundedPayload
import com.eventfulcommerce.settlement.service.SettlementService
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PaymentEventsConsumerTest {
    private lateinit var settlementService: SettlementService
    private lateinit var idempotencyHandler: IdempotencyHandler
    private lateinit var consumer: PaymentEventsConsumer

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setUp() {
        settlementService = mockk()
        idempotencyHandler = mockk()
        every<IdempotencyResult<Unit>> {
            idempotencyHandler.executeIdempotent(any<UUID>(), any<() -> Unit>())
        } answers {
            secondArg<() -> Unit>().invoke()
            IdempotencyResult.Success(Unit)
        }
        consumer = PaymentEventsConsumer(settlementService, idempotencyHandler, objectMapper)
    }

    @Test
    fun `PAYMENT_COMPLETED 이벤트는 판매자 주문별 정산 생성을 위임한다`() {
        val sellerOrder = PaymentCompletedSellerPayload(
            sellerOrderId = UUID.randomUUID(),
            sellerId = UUID.randomUUID(),
            paymentAmount = 20_000,
            commissionRate = 0.1,
            commissionAmount = 2_000,
            settlementAmount = 18_000
        )
        val payload = PaymentCompletedPayload(
            paymentId = UUID.randomUUID(),
            orderId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            amount = 20_000,
            sellerOrders = listOf(sellerOrder),
            completedAt = Instant.now()
        )
        every {
            settlementService.createSettlement(any(), any(), any(), any(), any(), any(), any(), any())
        } returns mockk(relaxed = true)

        consumer.consume(message("PAYMENT_COMPLETED", payload))

        verify(exactly = 1) {
            settlementService.createSettlement(
                paymentId = payload.paymentId,
                orderId = payload.orderId,
                sellerOrderId = sellerOrder.sellerOrderId,
                sellerId = sellerOrder.sellerId,
                userId = payload.userId,
                totalAmount = sellerOrder.paymentAmount,
                platformFee = sellerOrder.commissionAmount,
                sellerAmount = sellerOrder.settlementAmount
            )
        }
    }

    @Test
    fun `PAYMENT_REFUNDED 이벤트는 정산 환불 차감을 위임한다`() {
        val payload = PaymentRefundedPayload(
            refundId = UUID.randomUUID(),
            paymentId = UUID.randomUUID(),
            orderId = UUID.randomUUID(),
            sellerOrderId = UUID.randomUUID(),
            sellerId = UUID.randomUUID(),
            amount = 5_000,
            reason = "부분 취소",
            refundedAt = Instant.now()
        )
        every { settlementService.applyRefund(payload.sellerOrderId, payload.amount) } just Runs

        consumer.consume(message("PAYMENT_REFUNDED", payload))

        verify(exactly = 1) { settlementService.applyRefund(payload.sellerOrderId, payload.amount) }
    }

    private fun message(eventType: String, payload: Any): String =
        objectMapper.writeValueAsString(
            OutboxEventMessage(
                eventId = UUID.randomUUID(),
                aggregateType = "PAYMENT",
                aggregateId = UUID.randomUUID(),
                eventType = eventType,
                occurredAt = Instant.now(),
                payload = objectMapper.writeValueAsString(payload)
            )
        )
}
