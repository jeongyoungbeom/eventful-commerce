package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.exception.InsufficientInventoryException
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.time.Instant
import java.util.*

@DisplayName("주문 서비스 단위 테스트")
class OrdersServiceTest {

    private lateinit var ordersService: OrdersService
    private lateinit var ordersRepository: OrdersRepository
    private lateinit var outboxEventService: OutboxEventService
    private lateinit var inventoryReservationService: InventoryReservationService
    private lateinit var idempotencyHandler: IdempotencyHandler
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        ordersRepository = mockk()
        outboxEventService = mockk()
        inventoryReservationService = mockk()
        idempotencyHandler = mockk()
        objectMapper = ObjectMapper().apply {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
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

    @Test
    @DisplayName("재고가 충분하면 주문이 성공한다")
    fun `should create order successfully when stock is available`() {
        // Given
        val userId = UUID.randomUUID()
        val orderRequest = OrdersRequest(
            userId = userId.toString(),
            totalAmount = 10000L
        )

        val reservationId = UUID.randomUUID()
        val orderId = UUID.randomUUID()

        // saveAll 첫 번째 호출 (초기 저장)
        every { ordersRepository.saveAll(any<Iterable<Orders>>()) } answers {
            val orders = firstArg<Iterable<Orders>>().toList()
            orders.forEach {
                it.id = orderId
                it.createdAt = Instant.now()
            }
            orders
        }

        // reserve 성공
        every { inventoryReservationService.reserve(any(), any()) } returns reservationId

        // getStockSummary
        every { inventoryReservationService.getStockSummary() } just Runs

        // outbox 저장
        every { outboxEventService.record(any()) } just Runs

        // When
        val result = ordersService.orders(listOf(orderRequest))

        // Then
        assertEquals(1, result.size)
        assertEquals(orderId.toString(), result[0])

        verify(exactly = 1) { inventoryReservationService.reserve(orderId, any()) }
        verify(exactly = 1) { outboxEventService.record(any()) }
        verify(exactly = 2) { ordersRepository.saveAll(any<Iterable<Orders>>()) }
    }

    @Test
    @DisplayName("재고가 부족하면 InsufficientInventoryException이 발생한다")
    fun `should throw InsufficientInventoryException when stock is not available`() {
        // Given
        val userId = UUID.randomUUID()
        val orderRequest = OrdersRequest(userId = userId.toString(), totalAmount = 10000L)
        val orderId = UUID.randomUUID()

        every { ordersRepository.saveAll(any<Iterable<Orders>>()) } answers {
            val orders = firstArg<Iterable<Orders>>().toList()
            orders.forEach { it.id = orderId; it.createdAt = Instant.now() }
            orders
        }

        every { inventoryReservationService.reserve(any(), any()) } returns null
        every { outboxEventService.record(any()) } just Runs
        every { inventoryReservationService.getStockSummary() } just Runs
        every { inventoryReservationService.release(any()) } just Runs

        // When & Then
        val exception = assertThrows<InsufficientInventoryException> {
            ordersService.orders(listOf(orderRequest))
        }
        assertTrue(exception.message!!.contains("재고 부족"))

        // 초기 저장은 1번은 된다
        verify(exactly = 1) { ordersRepository.saveAll(any<Iterable<Orders>>()) }

        // 실패했으니 이후 단계는 호출되면 안 됨
        verify(exactly = 0) { outboxEventService.record(any()) }
        verify(exactly = 0) { inventoryReservationService.getStockSummary() }
        verify(exactly = 0) { inventoryReservationService.release(any()) }
    }


    @Test
    @DisplayName("일부만 실패해도 재고 부족으로 전체 실패하고, 기존 예약은 release된다")
    fun `일부 실패해도 재고 부족으로 실패`() {
        // Given
        val userId1 = UUID.randomUUID()
        val userId2 = UUID.randomUUID()

        val order1 = OrdersRequest(userId1.toString(), 10000L)
        val order2 = OrdersRequest(userId2.toString(), 20000L)

        val orderId1 = UUID.randomUUID()
        val orderId2 = UUID.randomUUID()
        val reservationId1 = UUID.randomUUID()

        every { ordersRepository.saveAll(any<Iterable<Orders>>()) } answers {
            val orders = firstArg<Iterable<Orders>>().toList()
            orders[0].id = orderId1
            orders[1].id = orderId2
            orders.forEach { it.createdAt = Instant.now() }
            orders
        }

        every { inventoryReservationService.reserve(orderId1, any()) } returns reservationId1
        every { inventoryReservationService.reserve(orderId2, any()) } returns null

        every { inventoryReservationService.release(any()) } just Runs
        every { outboxEventService.record(any()) } just Runs
        every { inventoryReservationService.getStockSummary() } just Runs

        // When & Then
        val ex = assertThrows<InsufficientInventoryException> {
            ordersService.orders(listOf(order1, order2))
        }

        // release는 성공했던 예약 1건만 호출되어야 함
        verify(exactly = 1) { inventoryReservationService.release(reservationId1) }

        // 초기 저장은 호출됨
        verify(exactly = 1) { ordersRepository.saveAll(any<Iterable<Orders>>()) }

        // 실패했으니 이후 단계는 호출되면 안 됨
        verify(exactly = 0) { outboxEventService.record(any()) }
        verify(exactly = 0) { inventoryReservationService.getStockSummary() }

        // (선택) 예외 메시지/failedOrderIds 검증
        // ex.failedOrderIds 가 String인지 List인지 네 정의에 맞춰서 확인 필요
        // assertTrue(ex.message!!.contains(orderId2.toString()))
    }

    @Test
    @DisplayName("모든 주문이 실패하면 이벤트가 발행되지 않는다")
    fun `should not publish events when all orders fail`() {
        // Given
        val userId = UUID.randomUUID()
        val orderRequest = OrdersRequest(userId.toString(), 10000L)

        val orderId = UUID.randomUUID()

        every { ordersRepository.saveAll(any<Iterable<Orders>>()) } answers {
            val orders = firstArg<Iterable<Orders>>().toList()
            orders.forEach { it.id = orderId }
            orders
        }

        // 모든 재고 예약 실패
        every { inventoryReservationService.reserve(any(), any()) } returns null

        // When & Then
        assertThrows<InsufficientInventoryException> {
            ordersService.orders(listOf(orderRequest))
        }

        // 이벤트 발행 없음
        verify(exactly = 0) { outboxEventService.record(any()) }
        verify(exactly = 0) { inventoryReservationService.getStockSummary() }
    }

    @Test
    @DisplayName("복수 주문이 모두 성공하면 모든 주문 ID가 반환된다")
    fun `should return all order IDs when multiple orders succeed`() {
        // Given
        val userId1 = UUID.randomUUID()
        val userId2 = UUID.randomUUID()
        val userId3 = UUID.randomUUID()

        val orders = listOf(
            OrdersRequest(userId1.toString(), 10000L),
            OrdersRequest(userId2.toString(), 20000L),
            OrdersRequest(userId3.toString(), 30000L)
        )

        val orderIds = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        every { ordersRepository.saveAll(any<Iterable<Orders>>()) } answers {
            val orderList = firstArg<Iterable<Orders>>().toList()
            orderList.forEachIndexed { index, order ->
                order.id = orderIds[index]
                order.createdAt = Instant.now()
            }
            orderList
        }

        // 모든 재고 예약 성공
        every { inventoryReservationService.reserve(any(), any()) } returns UUID.randomUUID()
        every { inventoryReservationService.getStockSummary() } just Runs
        every { outboxEventService.record(any()) } just Runs

        // When
        val result = ordersService.orders(orders)

        // Then
        assertEquals(3, result.size)
        assertEquals(orderIds.map { it.toString() }, result)

        verify(exactly = 1) { outboxEventService.record(match { it.size == 3 }) }
    }
}
