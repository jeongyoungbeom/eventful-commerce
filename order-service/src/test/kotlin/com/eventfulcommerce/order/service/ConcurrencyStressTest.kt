package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.exception.InsufficientInventoryException
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
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
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("주문 동시성 단위 테스트")
class ConcurrencyStressTest {

    private lateinit var ordersService: OrdersService
    private lateinit var ordersRepository: OrdersRepository
    private lateinit var outboxEventService: OutboxEventService
    private lateinit var inventoryReservationService: InventoryReservationService
    private lateinit var idempotencyHandler: IdempotencyHandler
    private lateinit var orderCancelService: OrderCancelService
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        ordersRepository = mockk()
        outboxEventService = mockk()
        inventoryReservationService = mockk()
        idempotencyHandler = mockk()
        orderCancelService = mockk()
        objectMapper = ObjectMapper().apply {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }

        every { outboxEventService.record(any()) } just Runs
        every { ordersRepository.saveAll(any<Iterable<Orders>>()) } answers {
            firstArg<Iterable<Orders>>().onEach { assignPersistenceFields(it) }.toList()
        }

        ordersService = OrdersService(
            ordersRepository = ordersRepository,
            outboxEventService = outboxEventService,
            inventoryReservationService = inventoryReservationService,
            idempotencyHandler = idempotencyHandler,
            objectMapper = objectMapper,
            orderCancelService = orderCancelService
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    @DisplayName("동시 주문 수가 재고와 같으면 모두 예약된다")
    fun `should reserve all concurrent orders when stock is enough`() {
        val result = runConcurrentOrders(stock = 100, requestCount = 100)

        assertEquals(100, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(0, result.remainingStock)
        verify(exactly = 100) { outboxEventService.record(match { it.size == 1 }) }
    }

    @Test
    @DisplayName("동시 주문 수가 재고보다 많아도 재고 수만 성공한다")
    fun `should prevent overselling when concurrent orders exceed stock`() {
        val result = runConcurrentOrders(stock = 10, requestCount = 50)

        assertEquals(10, result.successCount)
        assertEquals(40, result.failureCount)
        assertEquals(0, result.remainingStock)
        verify(exactly = 10) { outboxEventService.record(match { it.size == 1 }) }
    }

    @Test
    @DisplayName("복수 주문 배치에서 일부 예약 실패 시 기존 예약을 해제한다")
    fun `should release already reserved inventory when batch partially fails`() {
        val stock = AtomicInteger(1)
        val issuedReservations = mutableListOf<UUID>()
        val releasedReservations = mutableListOf<UUID>()

        every { inventoryReservationService.reserve(any(), any(), any()) } answers {
            if (stock.compareAndSet(1, 0)) {
                UUID.randomUUID().also { issuedReservations += it }
            } else {
                null
            }
        }
        every { inventoryReservationService.release(any(), any()) } answers {
            releasedReservations += secondArg<UUID>()
            stock.incrementAndGet()
            Unit
        }

        val requests = listOf(
            OrdersRequest(UUID.randomUUID().toString(), "PRODUCT-001", 10000L),
            OrdersRequest(UUID.randomUUID().toString(), "PRODUCT-001", 20000L)
        )

        val exception = assertThrows<InsufficientInventoryException> {
            ordersService.orders(requests)
        }

        assertTrue(exception.message!!.contains("재고 부족"))
        assertEquals(issuedReservations, releasedReservations)
        assertEquals(1, stock.get())
        verify(exactly = 0) { outboxEventService.record(any()) }
    }

    private fun runConcurrentOrders(stock: Int, requestCount: Int): ConcurrentOrderResult {
        val remainingStock = AtomicInteger(stock)
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        every { inventoryReservationService.reserve(any(), any(), any()) } answers {
            var reservationId: UUID? = null
            while (true) {
                val current = remainingStock.get()
                if (current <= 0) break
                if (remainingStock.compareAndSet(current, current - 1)) {
                    reservationId = UUID.randomUUID()
                    break
                }
            }
            reservationId
        }
        every { inventoryReservationService.release(any(), any()) } answers {
            remainingStock.incrementAndGet()
            Unit
        }

        val executor = Executors.newFixedThreadPool(minOf(requestCount, 32))
        val latch = CountDownLatch(requestCount)

        repeat(requestCount) {
            executor.submit {
                try {
                    ordersService.orders(
                        listOf(OrdersRequest(UUID.randomUUID().toString(), "PRODUCT-001", 10000L))
                    )
                    successCount.incrementAndGet()
                } catch (e: InsufficientInventoryException) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "동시 주문 처리가 제한 시간 안에 끝나야 합니다.")
        executor.shutdownNow()

        return ConcurrentOrderResult(
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            remainingStock = remainingStock.get()
        )
    }

    private fun assignPersistenceFields(order: Orders) {
        if (!isIdInitialized(order)) {
            order.id = UUID.randomUUID()
        }
        if (!isCreatedAtInitialized(order)) {
            order.createdAt = Instant.now()
        }
        if (!isUpdatedAtInitialized(order)) {
            order.updatedAt = Instant.now()
        }
        if (order.status != OrdersStatus.ORDER_RESERVED) {
            order.status = OrdersStatus.ORDER_RESERVED
        }
    }

    private fun isIdInitialized(order: Orders): Boolean =
        try {
            order.id
            true
        } catch (e: UninitializedPropertyAccessException) {
            false
        }

    private fun isCreatedAtInitialized(order: Orders): Boolean =
        try {
            order.createdAt
            true
        } catch (e: UninitializedPropertyAccessException) {
            false
        }

    private fun isUpdatedAtInitialized(order: Orders): Boolean =
        try {
            order.updatedAt
            true
        } catch (e: UninitializedPropertyAccessException) {
            false
        }

    private data class ConcurrentOrderResult(
        val successCount: Int,
        val failureCount: Int,
        val remainingStock: Int
    )
}
