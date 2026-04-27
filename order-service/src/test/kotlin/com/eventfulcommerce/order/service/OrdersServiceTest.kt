package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OrderReservedPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.exception.InsufficientInventoryException
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
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

@DisplayName("주문 서비스 단위 테스트")
class OrdersServiceTest {

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
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }

        every { ordersRepository.saveAll(any<Iterable<Orders>>()) } answers {
            firstArg<Iterable<Orders>>().onEach { assignPersistenceFields(it) }.toList()
        }
        every { outboxEventService.record(any()) } just Runs

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
    @DisplayName("재고가 충분하면 주문을 예약하고 ORDER_RESERVED 이벤트를 기록한다")
    fun `should create order and record reserved event when stock is available`() {
        val request = OrdersRequest(UUID.randomUUID().toString(), "PRODUCT-001", 10000L)
        val reservationId = UUID.randomUUID()
        val eventSlot = slot<List<OutboxEvent>>()

        every { inventoryReservationService.reserve("PRODUCT-001", any(), any()) } returns reservationId
        every { outboxEventService.record(capture(eventSlot)) } just Runs

        val result = ordersService.orders(listOf(request))

        assertEquals(1, result.size)
        assertTrue(result.first().isNotBlank())
        assertEquals(1, eventSlot.captured.size)
        assertEquals("ORDER_RESERVED", eventSlot.captured.first().eventType)

        val payload = objectMapper.readValue(eventSlot.captured.first().payload, OrderReservedPayload::class.java)
        assertEquals("PRODUCT-001", payload.productId)
        assertEquals(10000L, payload.totalAmount)
        assertEquals(reservationId, payload.reservationId)
        assertNotNull(payload.expiresAt)

        verify(exactly = 1) { inventoryReservationService.reserve("PRODUCT-001", any(), 600L) }
        verify(exactly = 2) { ordersRepository.saveAll(any<Iterable<Orders>>()) }
    }

    @Test
    @DisplayName("재고가 부족하면 예외를 던지고 이벤트를 기록하지 않는다")
    fun `should throw when stock is insufficient`() {
        val request = OrdersRequest(UUID.randomUUID().toString(), "PRODUCT-001", 10000L)

        every { inventoryReservationService.reserve("PRODUCT-001", any(), any()) } returns null

        val exception = assertThrows<InsufficientInventoryException> {
            ordersService.orders(listOf(request))
        }

        assertTrue(exception.message!!.contains("재고 부족"))
        verify(exactly = 0) { outboxEventService.record(any()) }
        verify(exactly = 0) { inventoryReservationService.release(any(), any()) }
    }

    @Test
    @DisplayName("배치 주문 일부가 실패하면 앞서 예약한 재고를 해제하고 전체 실패한다")
    fun `should release previous reservations when batch order partially fails`() {
        val firstRequest = OrdersRequest(UUID.randomUUID().toString(), "PRODUCT-001", 10000L)
        val secondRequest = OrdersRequest(UUID.randomUUID().toString(), "PRODUCT-001", 20000L)
        val reservationId = UUID.randomUUID()

        every { inventoryReservationService.reserve("PRODUCT-001", any(), any()) } returnsMany listOf(
            reservationId,
            null
        )
        every { inventoryReservationService.release("PRODUCT-001", reservationId) } just Runs

        assertThrows<InsufficientInventoryException> {
            ordersService.orders(listOf(firstRequest, secondRequest))
        }

        verify(exactly = 1) { inventoryReservationService.release("PRODUCT-001", reservationId) }
        verify(exactly = 0) { outboxEventService.record(any()) }
    }

    @Test
    @DisplayName("여러 주문이 모두 성공하면 모든 주문 ID와 이벤트를 반환한다")
    fun `should return all order IDs when multiple orders succeed`() {
        val requests = listOf(
            OrdersRequest(UUID.randomUUID().toString(), "PRODUCT-001", 10000L),
            OrdersRequest(UUID.randomUUID().toString(), "PRODUCT-002", 20000L),
            OrdersRequest(UUID.randomUUID().toString(), "PRODUCT-003", 30000L)
        )

        every { inventoryReservationService.reserve(any(), any(), any()) } returns UUID.randomUUID()

        val result = ordersService.orders(requests)

        assertEquals(3, result.size)
        verify(exactly = 1) { outboxEventService.record(match { it.size == 3 }) }
        verify(exactly = 1) { inventoryReservationService.reserve("PRODUCT-001", any(), 600L) }
        verify(exactly = 1) { inventoryReservationService.reserve("PRODUCT-002", any(), 600L) }
        verify(exactly = 1) { inventoryReservationService.reserve("PRODUCT-003", any(), 600L) }
    }

    @Test
    @DisplayName("사용자 취소는 OrderCancelService에 위임한다")
    fun `should delegate cancel order`() {
        val orderId = UUID.randomUUID()

        every { orderCancelService.cancel(orderId, "사용자 요청") } returns true

        val result = ordersService.cancelOrder(orderId)

        assertTrue(result)
        verify(exactly = 1) { orderCancelService.cancel(orderId, "사용자 요청") }
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
}
