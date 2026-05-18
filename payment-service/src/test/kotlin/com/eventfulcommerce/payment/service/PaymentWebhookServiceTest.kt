package com.eventfulcommerce.payment.service

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.PaymentCompletedSellerPayload
import com.eventfulcommerce.common.PaymentFailedPayload
import com.eventfulcommerce.payment.domain.PaymentStatus
import com.eventfulcommerce.payment.domain.PaymentWebhookRequest
import com.eventfulcommerce.payment.domain.entity.Payment
import com.eventfulcommerce.payment.repository.PaymentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class PaymentWebhookServiceTest {
    private lateinit var paymentRepository: PaymentRepository
    private lateinit var outboxEventRepository: com.eventfulcommerce.common.repository.OutboxEventRepository
    private lateinit var objectMapper: ObjectMapper
    private lateinit var paymentWebhookService: PaymentWebhookService

    @BeforeEach
    fun setUp() {
        paymentRepository = mock()
        outboxEventRepository = mock()
        objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        paymentWebhookService = PaymentWebhookService(
            paymentRepository = paymentRepository,
            outboxEventRepository = outboxEventRepository,
            objectMapper = objectMapper
        )
    }

    @Test
    fun `SUCCESS 웹훅은 결제를 완료 상태로 바꾸고 PAYMENT_COMPLETED 이벤트를 기록한다`() {
        val sellerOrder = paymentCompletedSellerPayload()
        val payment = paymentFixture(
            status = PaymentStatus.PAYMENT_RESERVED,
            amount = 20_000,
            sellerOrders = listOf(sellerOrder)
        )

        whenever(paymentRepository.findByOrderId(payment.orderId)).thenReturn(payment)
        whenever(outboxEventRepository.save(any<OutboxEvent>())).thenAnswer { it.arguments[0] as OutboxEvent }

        paymentWebhookService.handle(
            PaymentWebhookRequest(orderId = payment.orderId, result = "SUCCESS", pgTxId = "PG-1", amount = 20_000)
        )

        assertEquals(PaymentStatus.PAYMENT_COMPLETED, payment.status)
        verify(outboxEventRepository).save(
            argThat { event ->
                event.aggregateType == PaymentStatus.PAYMENT.toString() &&
                    event.aggregateId == payment.id &&
                    event.eventType == PaymentStatus.PAYMENT_COMPLETED.toString() &&
                    event.status == OutboxStatus.PENDING &&
                    objectMapper.readValue(event.payload, PaymentCompletedPayload::class.java).let {
                        it.paymentId == payment.id &&
                            it.orderId == payment.orderId &&
                            it.userId == payment.userId &&
                            it.amount == payment.amount &&
                            it.sellerOrders.single().sellerOrderId == sellerOrder.sellerOrderId
                    }
            }
        )
    }

    @Test
    fun `실패 웹훅은 결제를 실패 상태로 바꾸고 PAYMENT_FAILED 이벤트를 기록한다`() {
        val payment = paymentFixture(status = PaymentStatus.PAYMENT_RESERVED)

        whenever(paymentRepository.findByOrderId(payment.orderId)).thenReturn(payment)
        whenever(outboxEventRepository.save(any<OutboxEvent>())).thenAnswer { it.arguments[0] as OutboxEvent }

        paymentWebhookService.handle(
            PaymentWebhookRequest(orderId = payment.orderId, result = "FAILED", pgTxId = "PG-FAIL", amount = 20_000)
        )

        assertEquals(PaymentStatus.PAYMENT_FAILED, payment.status)
        verify(outboxEventRepository).save(
            argThat { event ->
                event.aggregateType == PaymentStatus.PAYMENT.toString() &&
                    event.aggregateId == payment.id &&
                    event.eventType == PaymentStatus.PAYMENT_FAILED.toString() &&
                    event.status == OutboxStatus.PENDING &&
                    objectMapper.readValue(event.payload, PaymentFailedPayload::class.java).let {
                        it.paymentId == payment.id &&
                            it.orderId == payment.orderId &&
                            it.amount == payment.amount &&
                            it.pgTxId == "PG-FAIL"
                    }
            }
        )
    }

    @Test
    fun `이미 예약 상태가 아닌 결제는 웹훅을 다시 받아도 이벤트를 기록하지 않는다`() {
        val payment = paymentFixture(status = PaymentStatus.PAYMENT_COMPLETED)

        whenever(paymentRepository.findByOrderId(payment.orderId)).thenReturn(payment)

        paymentWebhookService.handle(
            PaymentWebhookRequest(orderId = payment.orderId, result = "SUCCESS", pgTxId = "PG-DUP", amount = 20_000)
        )

        assertEquals(PaymentStatus.PAYMENT_COMPLETED, payment.status)
        verify(outboxEventRepository, never()).save(any<OutboxEvent>())
    }

    @Test
    fun `주문 ID에 해당하는 결제가 없으면 예외를 던지고 이벤트를 기록하지 않는다`() {
        val orderId = UUID.randomUUID()
        whenever(paymentRepository.findByOrderId(orderId)).thenReturn(null)

        assertThrows(IllegalArgumentException::class.java) {
            paymentWebhookService.handle(
                PaymentWebhookRequest(orderId = orderId, result = "SUCCESS", pgTxId = "PG-MISSING")
            )
        }

        verify(outboxEventRepository, never()).save(any<OutboxEvent>())
    }

    private fun paymentFixture(
        status: PaymentStatus,
        amount: Long = 20_000,
        sellerOrders: List<PaymentCompletedSellerPayload> = listOf(paymentCompletedSellerPayload())
    ): Payment {
        val payment = Payment(
            orderId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            status = status,
            amount = amount,
            sellerOrdersJson = objectMapper.writeValueAsString(sellerOrders)
        )
        setField(payment, "id", UUID.randomUUID())
        return payment
    }

    private fun paymentCompletedSellerPayload() = PaymentCompletedSellerPayload(
        sellerOrderId = UUID.randomUUID(),
        sellerId = UUID.randomUUID(),
        paymentAmount = 20_000,
        commissionRate = 0.1,
        commissionAmount = 2_000,
        settlementAmount = 18_000
    )

    private fun setField(target: Any, fieldName: String, value: Any) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
