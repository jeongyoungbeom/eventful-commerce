package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("멱등성 테스트")
class IdempotencyTest {

    private lateinit var ordersService: OrdersService
    private lateinit var ordersRepository: OrdersRepository
    private lateinit var outboxEventService: OutboxEventService
    private lateinit var inventoryReservationService: InventoryReservationService
    private lateinit var idempotencyHandler: IdempotencyHandler
    private lateinit var objectMapper: ObjectMapper
    private lateinit var processedEventRepository: ProcessedEventRepository

    @BeforeEach
    fun setUp() {
        ordersRepository = mockk()
        outboxEventService = mockk()
        inventoryReservationService = mockk()
        processedEventRepository = mockk()
        idempotencyHandler = IdempotencyHandler(processedEventRepository) // 실제 구현 사용
        objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }

        ordersService = OrdersService(
            ordersRepository = ordersRepository,
            outboxEventService = outboxEventService,
            inventoryReservationService = inventoryReservationService,
            idempotencyHandler = idempotencyHandler,
            objectMapper = objectMapper
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    private fun Orders.setId(id: UUID) {
        val field = Orders::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.set(this, id)
    }

    @Test
    @DisplayName("동일한 이벤트를 여러 번 처리해도 한 번만 실행된다")
    fun `should process same event only once - idempotency test`() {
        // Given
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()

        var called = 0
        every { processedEventRepository.save(any()) } answers {
            called++
            if (called == 1) firstArg()
            else throw DataIntegrityViolationException("duplicate")
        }

        val order = Orders(
            userId = UUID.randomUUID(),
            totalAmount = 10000L,
            status = OrdersStatus.ORDER_RESERVED,
            reservationId = reservationId
        ).apply { setId(orderId) }

        val payload = PaymentCompletedPayload(
            orderId = orderId,
            amount = order.totalAmount,
            paymentId = UUID.randomUUID(),
            completedAt = Instant.now()
        )

        val event = OutboxEventMessage(
            eventId = eventId,
            aggregateType = "ORDER",
            aggregateId = orderId,
            eventType = "PAYMENT_COMPLETED",
            payload = objectMapper.writeValueAsString(payload),
            occurredAt = Instant.now()
        )

        every { ordersRepository.findById(orderId) } returns Optional.of(order)
        every { ordersRepository.save(any()) } returns order
        every { inventoryReservationService.commit(reservationId) } just Runs
        every { outboxEventService.record(any<List<OutboxEvent>>()) } just Runs


        // When: 동일한 이벤트를 3번 처리
        ordersService.handlePaymentCompleted(event)
        ordersService.handlePaymentCompleted(event)
        ordersService.handlePaymentCompleted(event)

        // Then: commit과 save는 1번만 실행되어야 함
        verify(exactly = 1) { inventoryReservationService.commit(reservationId) }
        verify(exactly = 1) { ordersRepository.save(order) }
        verify(exactly = 1) { outboxEventService.record(any<List<OutboxEvent>>()) }

    }

    @Test
    @DisplayName("동시에 동일한 이벤트를 처리해도 한 번만 실행된다 (동시성)")
    fun `should handle concurrent duplicate events with idempotency`() {
        // Given
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()

        val counter = AtomicInteger(0)
        every { processedEventRepository.save(any()) } answers {
            if (counter.incrementAndGet() == 1) firstArg()
            else throw DataIntegrityViolationException("duplicate")
        }


        val order = Orders(
            userId = UUID.randomUUID(),
            totalAmount = 10000L,
            status = OrdersStatus.ORDER_RESERVED,
            reservationId = reservationId
        ).apply { setId(orderId) }

        val payload = PaymentCompletedPayload(
            orderId = orderId,
            amount = order.totalAmount,
            paymentId = UUID.randomUUID(),
            completedAt = Instant.now()
        )

        val event = OutboxEventMessage(
            eventId = eventId,
            aggregateType = "ORDER",
            aggregateId = orderId,
            eventType = "PAYMENT_COMPLETED",
            payload = objectMapper.writeValueAsString(payload),
            occurredAt = Instant.now()
        )

        every { ordersRepository.findById(orderId) } returns Optional.of(order)
        every { ordersRepository.save(any()) } returns order
        every { inventoryReservationService.commit(reservationId) } just Runs
        every { outboxEventService.record(any<List<OutboxEvent>>()) } just Runs

        // When: 10개 스레드에서 동시에 동일한 이벤트 처리
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

        latch.await()
        executor.shutdown()

        // Then: commit과 save는 1번만 실행되어야 함
        verify(exactly = 1) { inventoryReservationService.commit(reservationId) }
        verify(exactly = 1) { ordersRepository.save(any()) }
        verify(exactly = 1) { outboxEventService.record(any<List<OutboxEvent>>()) }
    }

    @Test
    @DisplayName("다른 이벤트 ID는 각각 처리된다")
    fun `should process different event IDs separately`() {
        // Given
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()

        val counter = AtomicInteger(0)
        every { processedEventRepository.save(any()) } answers {
            if (counter.incrementAndGet() == 1) firstArg()
            else throw DataIntegrityViolationException("duplicate")
        }

        val order = Orders(
            userId = UUID.randomUUID(),
            totalAmount = 10000L,
            status = OrdersStatus.ORDER_RESERVED,
            reservationId = reservationId
        ).apply { setId(orderId) }

        val payload = PaymentCompletedPayload(
            orderId = orderId,
            amount = order.totalAmount,
            paymentId = UUID.randomUUID(),
            completedAt = Instant.now()
        )

        // 서로 다른 이벤트 ID
        val event1 = OutboxEventMessage(
            eventId = UUID.randomUUID(),
            aggregateType = "ORDER",
            aggregateId = orderId,
            eventType = "PAYMENT_COMPLETED",
            payload = objectMapper.writeValueAsString(payload),
            occurredAt = Instant.now()
        )

        val event2 = OutboxEventMessage(
            eventId = UUID.randomUUID(),
            aggregateType = "ORDER",
            aggregateId = orderId,
            eventType = "PAYMENT_COMPLETED",
            payload = objectMapper.writeValueAsString(payload),
            occurredAt = Instant.now()
        )

        every { ordersRepository.findById(orderId) } returns Optional.of(order)
        every { ordersRepository.save(any()) } returns order
        every { inventoryReservationService.commit(reservationId) } just Runs
        every { outboxEventService.record(any()) } just Runs

        // When: 다른 이벤트 ID로 2번 처리
        ordersService.handlePaymentCompleted(event1)
        
        // 첫 번째 처리 후 상태 변경
        order.status = OrdersStatus.ORDER_CONFIRMED
        
        ordersService.handlePaymentCompleted(event2)

        // Then: 첫 번째만 처리됨 (두 번째는 이미 CONFIRMED 상태라 스킵)
        verify(exactly = 1) { inventoryReservationService.commit(reservationId) }
        verify(exactly = 1) { outboxEventService.record(any()) }
    }

    @Test
    @DisplayName("⏱️ 이미 처리된 이벤트는 즉시 반환된다 (성능)")
    fun `should return immediately for already processed events`() {
        // Given
        val eventId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()

        val counter = AtomicInteger(0)
        every { processedEventRepository.save(any()) } answers {
            if (counter.incrementAndGet() == 1) firstArg()
            else throw DataIntegrityViolationException("duplicate")
        }

        val order = Orders(
            userId = UUID.randomUUID(),
            totalAmount = 10000L,
            status = OrdersStatus.ORDER_RESERVED,
            reservationId = reservationId
        ).apply { setId(orderId) }

        val payload = PaymentCompletedPayload(
            orderId = orderId,
            amount = order.totalAmount,
            paymentId = UUID.randomUUID(),
            completedAt = Instant.now()
        )

        val event = OutboxEventMessage(
            eventId = eventId,
            aggregateType = "ORDER",
            aggregateId = orderId,
            eventType = "PAYMENT_COMPLETED",
            payload = objectMapper.writeValueAsString(payload),
            occurredAt = Instant.now()
        )

        every { ordersRepository.findById(orderId) } returns Optional.of(order)
        every { ordersRepository.save(any()) } returns order
        every { inventoryReservationService.commit(reservationId) } just Runs
        every { outboxEventService.record(any()) } just Runs

        // When: 첫 번째 처리
        val start1 = System.nanoTime()
        ordersService.handlePaymentCompleted(event)
        val duration1 = System.nanoTime() - start1

        // When: 두 번째 처리 (이미 처리됨)
        val start2 = System.nanoTime()
        ordersService.handlePaymentCompleted(event)
        val duration2 = System.nanoTime() - start2

        // Then: 두 번째 호출이 훨씬 빨라야 함 (실제 처리 없이 바로 반환)
        assertTrue(duration2 < duration1 / 2, 
            "두 번째 호출이 첫 번째보다 빨라야 함: duration1=$duration1, duration2=$duration2")
        
        verify(exactly = 1) { inventoryReservationService.commit(reservationId) }
    }
}
