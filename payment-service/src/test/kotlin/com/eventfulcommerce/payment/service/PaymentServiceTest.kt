package com.eventfulcommerce.payment.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.IdempotencyResult
import com.eventfulcommerce.common.OrderCanceledItemPayload
import com.eventfulcommerce.common.OrderCanceledPayload
import com.eventfulcommerce.common.OrderCanceledSellerPayload
import com.eventfulcommerce.common.OrderReservedItemPayload
import com.eventfulcommerce.common.OrderReservedPayload
import com.eventfulcommerce.common.OrderReservedSellerPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.PaymentRefundedPayload
import com.eventfulcommerce.common.PaymentCompletedSellerPayload
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.payment.domain.PaymentStatus
import com.eventfulcommerce.payment.domain.entity.Payment
import com.eventfulcommerce.payment.domain.entity.PaymentRefund
import com.eventfulcommerce.payment.repository.PaymentRefundRepository
import com.eventfulcommerce.payment.repository.PaymentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

class PaymentServiceTest {
    private lateinit var objectMapper: ObjectMapper
    private lateinit var idempotencyHandler: IdempotencyHandler
    private lateinit var paymentRepository: PaymentRepository
    private lateinit var paymentRefundRepository: PaymentRefundRepository
    private lateinit var outboxEventRepository: OutboxEventRepository
    private lateinit var paymentService: PaymentService

    @BeforeEach
    fun setUp() {
        objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        idempotencyHandler = mock()
        paymentRepository = mock()
        paymentRefundRepository = mock()
        outboxEventRepository = mock()

        whenever(idempotencyHandler.executeIdempotent<Unit>(any<UUID>(), any())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            val action = it.arguments[1] as () -> Unit
            action.invoke()
            IdempotencyResult.Success(Unit)
        }

        paymentService = PaymentService(
            objectMapper = objectMapper,
            idempotencyHandler = idempotencyHandler,
            paymentRepository = paymentRepository,
            paymentRefundRepository = paymentRefundRepository,
            outboxEventRepository = outboxEventRepository
        )
    }

    @Test
    fun `ORDER_RESERVED 이벤트를 받으면 PAYMENT_RESERVED 결제 레코드를 생성한다`() {
        val eventId = UUID.randomUUID()
        val payload = orderReservedPayload()
        val message = orderReservedMessage(eventId, payload)

        whenever(paymentRepository.findByOrderId(payload.orderId)).thenReturn(null)
        whenever(paymentRepository.save(any<Payment>())).thenAnswer { it.arguments[0] as Payment }

        paymentService.handleOrderCreated(message)

        verify(idempotencyHandler).executeIdempotent(eq(eventId), any<() -> Unit>())
        verify(paymentRepository).save(
            argThat { payment ->
                payment.orderId == payload.orderId &&
                    payment.userId == payload.userId &&
                    payment.status == PaymentStatus.PAYMENT_RESERVED &&
                    payment.amount == payload.totalPaymentAmount &&
                    objectMapper.readValue(
                        payment.sellerOrdersJson,
                        Array<PaymentCompletedSellerPayload>::class.java
                    ).single().let {
                        it.sellerOrderId == payload.sellerOrders.single().sellerOrderId &&
                            it.sellerId == payload.sellerOrders.single().sellerId &&
                            it.paymentAmount == payload.sellerOrders.single().paymentAmount &&
                            it.commissionRate == payload.sellerOrders.single().commissionRate &&
                            it.commissionAmount == payload.sellerOrders.single().commissionAmount &&
                            it.settlementAmount == payload.sellerOrders.single().settlementAmount
                    }
            }
        )
    }

    @Test
    fun `이미 결제가 존재하는 주문 이벤트는 결제 레코드를 다시 생성하지 않는다`() {
        val eventId = UUID.randomUUID()
        val payload = orderReservedPayload()
        val message = orderReservedMessage(eventId, payload)
        val existingPayment = paymentFixture(payload.orderId, payload.userId, payload.totalPaymentAmount)

        whenever(paymentRepository.findByOrderId(payload.orderId)).thenReturn(existingPayment)

        paymentService.handleOrderCreated(message)

        verify(idempotencyHandler).executeIdempotent(eq(eventId), any<() -> Unit>())
        verify(paymentRepository, never()).save(any<Payment>())
    }

    @Test
    fun `ORDER_CANCELED 이벤트를 받으면 환불 레코드와 PAYMENT_REFUNDED 이벤트를 생성한다`() {
        val eventId = UUID.randomUUID()
        val payment = paymentFixture(
            orderId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            amount = 20_000,
            status = PaymentStatus.PAYMENT_COMPLETED
        )
        setField(payment, "id", UUID.randomUUID())
        val payload = orderCanceledPayload(
            orderId = payment.orderId,
            userId = payment.userId,
            refundAmount = 20_000
        )
        val message = orderCanceledMessage(eventId, payload)

        whenever(paymentRepository.findByOrderId(payment.orderId)).thenReturn(payment)
        whenever(
            paymentRefundRepository.findByPaymentIdAndSellerOrderId(
                payment.id,
                payload.canceledSellerOrders.single().sellerOrderId
            )
        ).thenReturn(null)
        whenever(paymentRefundRepository.save(any<PaymentRefund>())).thenAnswer {
            (it.arguments[0] as PaymentRefund).also { refund ->
                setField(refund, "id", UUID.randomUUID())
            }
        }
        whenever(paymentRepository.save(any<Payment>())).thenAnswer { it.arguments[0] as Payment }

        paymentService.handleOrderCanceled(message)

        assertEquals(20_000, payment.refundedAmount)
        assertEquals(PaymentStatus.PAYMENT_REFUNDED, payment.status)
        verify(idempotencyHandler).executeIdempotent(eq(eventId), any<() -> Unit>())
        verify(paymentRefundRepository).save(
            argThat { refund ->
                refund.payment == payment &&
                    refund.orderId == payload.orderId &&
                    refund.sellerOrderId == payload.canceledSellerOrders.single().sellerOrderId &&
                    refund.sellerId == payload.canceledSellerOrders.single().sellerId &&
                    refund.amount == payload.canceledSellerOrders.single().refundAmount &&
                    refund.reason == payload.reason
            }
        )
        verify(paymentRepository).save(payment)
        verify(outboxEventRepository).saveAll(
            argThat<List<OutboxEvent>> { events ->
                events.size == 1 &&
                    events.single().aggregateType == "PAYMENT_REFUND" &&
                    events.single().eventType == "PAYMENT_REFUNDED" &&
                    objectMapper.readValue(events.single().payload, PaymentRefundedPayload::class.java).let {
                        it.paymentId == payment.id &&
                            it.orderId == payment.orderId &&
                            it.amount == payload.canceledSellerOrders.single().refundAmount &&
                            it.reason == payload.reason
                    }
            }
        )
    }

    @Test
    fun `부분 취소 금액이면 결제를 PAYMENT_PARTIALLY_REFUNDED 상태로 변경한다`() {
        val eventId = UUID.randomUUID()
        val payment = paymentFixture(
            orderId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            amount = 20_000,
            status = PaymentStatus.PAYMENT_COMPLETED
        )
        setField(payment, "id", UUID.randomUUID())
        val payload = orderCanceledPayload(
            orderId = payment.orderId,
            userId = payment.userId,
            refundAmount = 5_000
        )
        val message = orderCanceledMessage(eventId, payload)

        whenever(paymentRepository.findByOrderId(payment.orderId)).thenReturn(payment)
        whenever(paymentRefundRepository.findByPaymentIdAndSellerOrderId(any<UUID>(), any<UUID>())).thenReturn(null)
        whenever(paymentRefundRepository.save(any<PaymentRefund>())).thenAnswer {
            (it.arguments[0] as PaymentRefund).also { refund ->
                setField(refund, "id", UUID.randomUUID())
            }
        }
        whenever(paymentRepository.save(any<Payment>())).thenAnswer { it.arguments[0] as Payment }

        paymentService.handleOrderCanceled(message)

        assertEquals(5_000, payment.refundedAmount)
        assertEquals(PaymentStatus.PAYMENT_PARTIALLY_REFUNDED, payment.status)
        verify(idempotencyHandler).executeIdempotent(eq(eventId), any<() -> Unit>())
        verify(paymentRefundRepository).save(any<PaymentRefund>())
        verify(outboxEventRepository).saveAll(any<List<OutboxEvent>>())
    }

    @Test
    fun `이미 환불 레코드가 있는 판매자 주문은 중복 환불 이벤트를 만들지 않는다`() {
        val eventId = UUID.randomUUID()
        val payment = paymentFixture(
            orderId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            amount = 20_000,
            status = PaymentStatus.PAYMENT_COMPLETED
        )
        setField(payment, "id", UUID.randomUUID())
        val payload = orderCanceledPayload(
            orderId = payment.orderId,
            userId = payment.userId,
            refundAmount = 20_000
        )
        val message = orderCanceledMessage(eventId, payload)
        val existingRefund = PaymentRefund(
            payment = payment,
            orderId = payload.orderId,
            sellerOrderId = payload.canceledSellerOrders.single().sellerOrderId,
            sellerId = payload.canceledSellerOrders.single().sellerId,
            amount = 20_000,
            reason = payload.reason
        )

        whenever(paymentRepository.findByOrderId(payment.orderId)).thenReturn(payment)
        whenever(
            paymentRefundRepository.findByPaymentIdAndSellerOrderId(
                payment.id,
                payload.canceledSellerOrders.single().sellerOrderId
            )
        ).thenReturn(existingRefund)
        whenever(paymentRepository.save(any<Payment>())).thenAnswer { it.arguments[0] as Payment }

        paymentService.handleOrderCanceled(message)

        assertEquals(0, payment.refundedAmount)
        assertEquals(PaymentStatus.PAYMENT_PARTIALLY_REFUNDED, payment.status)
        verify(paymentRefundRepository, never()).save(any<PaymentRefund>())
        verify(outboxEventRepository, never()).saveAll(any<List<OutboxEvent>>())
    }

    @Test
    fun `완료되지 않은 결제는 주문 취소 이벤트를 받아도 환불하지 않는다`() {
        val eventId = UUID.randomUUID()
        val payment = paymentFixture(
            orderId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            amount = 20_000,
            status = PaymentStatus.PAYMENT_RESERVED
        )
        val payload = orderCanceledPayload(
            orderId = payment.orderId,
            userId = payment.userId,
            refundAmount = 20_000
        )

        whenever(paymentRepository.findByOrderId(payment.orderId)).thenReturn(payment)

        paymentService.handleOrderCanceled(orderCanceledMessage(eventId, payload))

        assertEquals(PaymentStatus.PAYMENT_RESERVED, payment.status)
        verify(idempotencyHandler).executeIdempotent(eq(eventId), any<() -> Unit>())
        verify(paymentRefundRepository, never()).save(any<PaymentRefund>())
        verify(paymentRepository, never()).save(any<Payment>())
        verify(outboxEventRepository, never()).saveAll(any<List<OutboxEvent>>())
    }

    private fun orderReservedMessage(eventId: UUID, payload: OrderReservedPayload) = OutboxEventMessage(
        eventId = eventId,
        aggregateType = "ORDER",
        aggregateId = payload.orderId,
        eventType = "ORDER_RESERVED",
        occurredAt = Instant.now(),
        payload = objectMapper.writeValueAsString(payload)
    )

    private fun orderCanceledMessage(eventId: UUID, payload: OrderCanceledPayload) = OutboxEventMessage(
        eventId = eventId,
        aggregateType = "ORDER",
        aggregateId = payload.orderId,
        eventType = "ORDER_CANCELED",
        occurredAt = Instant.now(),
        payload = objectMapper.writeValueAsString(payload)
    )

    private fun orderReservedPayload(): OrderReservedPayload {
        val sellerOrderId = UUID.randomUUID()
        val sellerId = UUID.randomUUID()
        val itemTotalAmount = 20_000L
        val commissionAmount = 2_000L
        val settlementAmount = 18_000L

        return OrderReservedPayload(
            orderId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            totalItemAmount = itemTotalAmount,
            totalDeliveryFee = 0,
            totalPaymentAmount = itemTotalAmount,
            totalCommissionAmount = commissionAmount,
            totalSettlementAmount = settlementAmount,
            sellerOrders = listOf(
                OrderReservedSellerPayload(
                    sellerOrderId = sellerOrderId,
                    sellerId = sellerId,
                    itemTotalAmount = itemTotalAmount,
                    deliveryFee = 0,
                    paymentAmount = itemTotalAmount,
                    commissionRate = 0.1,
                    commissionAmount = commissionAmount,
                    settlementAmount = settlementAmount,
                    items = listOf(
                        OrderReservedItemPayload(
                            orderItemId = UUID.randomUUID(),
                            productId = UUID.randomUUID(),
                            productName = "결제 예약 테스트 상품",
                            quantity = 2,
                            unitPrice = 10_000,
                            totalAmount = itemTotalAmount,
                            reservationId = UUID.randomUUID()
                        )
                    )
                )
            ),
            expiresAt = Instant.now().plusSeconds(600),
            createdAt = Instant.now()
        )
    }

    private fun orderCanceledPayload(orderId: UUID, userId: UUID, refundAmount: Long): OrderCanceledPayload {
        val sellerOrderId = UUID.randomUUID()
        val sellerId = UUID.randomUUID()
        return OrderCanceledPayload(
            orderId = orderId,
            userId = userId,
            reason = "사용자 요청",
            canceledSellerOrders = listOf(
                OrderCanceledSellerPayload(
                    sellerOrderId = sellerOrderId,
                    sellerId = sellerId,
                    refundAmount = refundAmount,
                    items = listOf(
                        OrderCanceledItemPayload(
                            orderItemId = UUID.randomUUID(),
                            productId = UUID.randomUUID(),
                            quantity = 1,
                            amount = refundAmount
                        )
                    )
                )
            )
        )
    }

    private fun paymentFixture(
        orderId: UUID,
        userId: UUID,
        amount: Long,
        status: PaymentStatus = PaymentStatus.PAYMENT_RESERVED
    ) = Payment(
        orderId = orderId,
        userId = userId,
        status = status,
        amount = amount,
        sellerOrdersJson = "[]"
    )

    private fun setField(target: Any, fieldName: String, value: Any) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
