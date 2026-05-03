package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.PaymentFailedPayload
import com.eventfulcommerce.common.ProcessedEvent
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("주문 이벤트 멱등성 테스트")
class IdempotencyTest {

    private lateinit var ordersService: OrdersService
    private lateinit var ordersRepository: OrdersRepository
    private lateinit var outboxEventService: OutboxEventService
    private lateinit var inventoryReservationService: InventoryReservationService
    private lateinit var processedEventRepository: ProcessedEventRepository
    private lateinit var orderCancelService: OrderCancelService
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        ordersRepository = mockk()
        outboxEventService = mockk()
        inventoryReservationService = mockk()
        processedEventRepository = mockk()
        orderCancelService = mockk()
        objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }

        ordersService = OrdersService(
            ordersRepository = ordersRepository,
            productReadModelRepository = mockk(),
            outboxEventService = outboxEventService,
            inventoryReservationService = inventoryReservationService,
            idempotencyHandler = IdempotencyHandler(processedEventRepository),
            objectMapper = objectMapper,
            orderCancelService = orderCancelService
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    @DisplayName("동일한 결제 완료 이벤트는 한 번만 주문을 확정한다")
    fun `should process duplicate payment completed event only once`() {
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val order = reservedOrder(orderId, reservationId)
        val event = paymentCompletedEvent(eventId, order)
        val saveAttempts = AtomicInteger(0)

        every { processedEventRepository.save(any()) } answers {
            if (saveAttempts.incrementAndGet() == 1) firstArg<ProcessedEvent>()
            else throw DataIntegrityViolationException("duplicate event")
        }
        every { ordersRepository.findById(orderId) } returns Optional.of(order)
        every { inventoryReservationService.commit(order.productId.toString(), reservationId) } just Runs
        every { ordersRepository.save(order) } returns order
        every { outboxEventService.record(any()) } just Runs

        repeat(3) {
            ordersService.handlePaymentCompleted(event)
        }

        assertEquals(OrdersStatus.ORDER_CONFIRMED, order.status)
        verify(exactly = 1) { inventoryReservationService.commit(order.productId.toString(), reservationId) }
        verify(exactly = 1) { ordersRepository.save(order) }
        verify(exactly = 1) { outboxEventService.record(match { it.size == 1 }) }
    }

    @Test
    @DisplayName("동시에 중복 결제 완료 이벤트가 들어와도 비즈니스 로직은 한 번만 실행된다")
    fun `should handle concurrent duplicate payment events once`() {
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val order = reservedOrder(orderId, reservationId)
        val event = paymentCompletedEvent(eventId, order)
        val saveAttempts = AtomicInteger(0)

        every { processedEventRepository.save(any()) } answers {
            if (saveAttempts.incrementAndGet() == 1) firstArg<ProcessedEvent>()
            else throw DataIntegrityViolationException("duplicate event")
        }
        every { ordersRepository.findById(orderId) } returns Optional.of(order)
        every { inventoryReservationService.commit(order.productId.toString(), reservationId) } just Runs
        every { ordersRepository.save(order) } returns order
        every { outboxEventService.record(any()) } just Runs

        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        repeat(threadCount) {
            executor.submit {
                try {
                    ordersService.handlePaymentCompleted(event)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "동시 이벤트 처리가 제한 시간 안에 끝나야 합니다.")
        executor.shutdownNow()

        verify(exactly = 1) { inventoryReservationService.commit(order.productId.toString(), reservationId) }
        verify(exactly = 1) { ordersRepository.save(order) }
        verify(exactly = 1) { outboxEventService.record(any()) }
    }

    @Test
    @DisplayName("서로 다른 이벤트라도 이미 확정된 주문이면 재확정하지 않는다")
    fun `should skip already confirmed order for a different event`() {
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val order = reservedOrder(orderId, reservationId)
        val firstEvent = paymentCompletedEvent(UUID.randomUUID(), order)
        val secondEvent = paymentCompletedEvent(UUID.randomUUID(), order)

        every { processedEventRepository.save(any()) } answers { firstArg<ProcessedEvent>() }
        every { ordersRepository.findById(orderId) } returns Optional.of(order)
        every { inventoryReservationService.commit(order.productId.toString(), reservationId) } just Runs
        every { ordersRepository.save(order) } returns order
        every { outboxEventService.record(any()) } just Runs

        ordersService.handlePaymentCompleted(firstEvent)
        ordersService.handlePaymentCompleted(secondEvent)

        verify(exactly = 2) { processedEventRepository.save(any()) }
        verify(exactly = 1) { inventoryReservationService.commit(order.productId.toString(), reservationId) }
        verify(exactly = 1) { ordersRepository.save(order) }
        verify(exactly = 1) { outboxEventService.record(any()) }
    }

    @Test
    @DisplayName("중복 결제 실패 이벤트는 주문 취소를 한 번만 위임한다")
    fun `should delegate duplicate payment failed event only once`() {
        val eventId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val event = paymentFailedEvent(eventId, paymentId, orderId, reservationId)
        val saveAttempts = AtomicInteger(0)

        every { processedEventRepository.save(any()) } answers {
            if (saveAttempts.incrementAndGet() == 1) firstArg<ProcessedEvent>()
            else throw DataIntegrityViolationException("duplicate event")
        }
        every { orderCancelService.cancel(orderId, "결제 실패") } returns true

        repeat(3) {
            ordersService.handlePaymentFailed(event)
        }

        verify(exactly = 1) { orderCancelService.cancel(orderId, "결제 실패") }
    }

    private fun reservedOrder(orderId: UUID, reservationId: UUID): Orders =
        Orders(
            userId = UUID.randomUUID(),
            productId = UUID.randomUUID(),
            sellerId = UUID.randomUUID(),
            quantity = 1,
            totalAmount = 10000L,
            status = OrdersStatus.ORDER_RESERVED,
            reservationId = reservationId,
            expiresAt = Instant.now().plusSeconds(600)
        ).apply {
            id = orderId
            createdAt = Instant.now()
            updatedAt = Instant.now()
        }

    private fun paymentCompletedEvent(eventId: UUID, order: Orders): OutboxEventMessage {
        val payload = PaymentCompletedPayload(
            paymentId = UUID.randomUUID(),
            reservationId = order.reservationId,
            orderId = order.id,
            userId = order.userId,
            sellerId = order.sellerId,
            amount = order.totalAmount,
            completedAt = Instant.now()
        )

        return OutboxEventMessage(
            eventId = eventId,
            aggregateType = "PAYMENT",
            aggregateId = order.id,
            eventType = "PAYMENT_COMPLETED",
            occurredAt = Instant.now(),
            payload = objectMapper.writeValueAsString(payload)
        )
    }

    private fun paymentFailedEvent(
        eventId: UUID,
        paymentId: UUID,
        orderId: UUID,
        reservationId: UUID
    ): OutboxEventMessage {
        val payload = PaymentFailedPayload(
            paymentId = paymentId,
            orderId = orderId,
            amount = 10000L,
            reservationId = reservationId,
            failedAt = Instant.now(),
            pgTxId = "PG-TX-FAILED"
        )

        return OutboxEventMessage(
            eventId = eventId,
            aggregateType = "PAYMENT",
            aggregateId = paymentId,
            eventType = "PAYMENT_FAILED",
            occurredAt = Instant.now(),
            payload = objectMapper.writeValueAsString(payload)
        )
    }
}
