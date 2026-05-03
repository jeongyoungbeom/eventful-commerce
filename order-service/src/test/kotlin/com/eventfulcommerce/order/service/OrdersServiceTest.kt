package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OrderReservedPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.domain.entity.ProductReadModel
import com.eventfulcommerce.order.exception.InsufficientInventoryException
import com.eventfulcommerce.order.repository.OrdersRepository
import com.eventfulcommerce.order.repository.ProductReadModelRepository
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
import java.util.Optional
import java.util.UUID

@DisplayName("주문 서비스 단위 테스트")
class OrdersServiceTest {

    private lateinit var ordersService: OrdersService
    private lateinit var ordersRepository: OrdersRepository
    private lateinit var productReadModelRepository: ProductReadModelRepository
    private lateinit var outboxEventService: OutboxEventService
    private lateinit var inventoryReservationService: InventoryReservationService
    private lateinit var idempotencyHandler: IdempotencyHandler
    private lateinit var orderCancelService: OrderCancelService
    private lateinit var objectMapper: ObjectMapper

    private val productId1 = UUID.randomUUID()
    private val productId2 = UUID.randomUUID()
    private val productId3 = UUID.randomUUID()
    private val sellerId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        ordersRepository = mockk()
        productReadModelRepository = mockk()
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

        // 기본 상품 조회 mock
        every { productReadModelRepository.findById(productId1) } returns Optional.of(
            ProductReadModel(productId = productId1, sellerId = sellerId, name = "상품1", price = 10000L, stock = 100, category = "ELECTRONICS")
        )
        every { productReadModelRepository.findById(productId2) } returns Optional.of(
            ProductReadModel(productId = productId2, sellerId = sellerId, name = "상품2", price = 20000L, stock = 100, category = "ELECTRONICS")
        )
        every { productReadModelRepository.findById(productId3) } returns Optional.of(
            ProductReadModel(productId = productId3, sellerId = sellerId, name = "상품3", price = 30000L, stock = 100, category = "ELECTRONICS")
        )

        ordersService = OrdersService(
            ordersRepository = ordersRepository,
            productReadModelRepository = productReadModelRepository,
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
        val request = OrdersRequest(productId1, 1)
        val reservationId = UUID.randomUUID()
        val eventSlot = slot<List<OutboxEvent>>()

        every { inventoryReservationService.reserve(productId1.toString(), any(), any()) } returns reservationId
        every { outboxEventService.record(capture(eventSlot)) } just Runs

        val result = ordersService.orders(listOf(request), UUID.randomUUID())

        assertEquals(1, result.size)
        assertTrue(result.first().isNotBlank())
        assertEquals(1, eventSlot.captured.size)
        assertEquals("ORDER_RESERVED", eventSlot.captured.first().eventType)

        val payload = objectMapper.readValue(eventSlot.captured.first().payload, OrderReservedPayload::class.java)
        assertEquals(productId1, payload.productId)
        assertEquals(sellerId, payload.sellerId)
        assertEquals(10000L, payload.totalAmount)
        assertEquals(1, payload.quantity)
        assertEquals(reservationId, payload.reservationId)
        assertNotNull(payload.expiresAt)

        verify(exactly = 1) { inventoryReservationService.reserve(productId1.toString(), any(), 600L) }
        verify(exactly = 2) { ordersRepository.saveAll(any<Iterable<Orders>>()) }
    }

    @Test
    @DisplayName("재고가 부족하면 예외를 던지고 이벤트를 기록하지 않는다")
    fun `should throw when stock is insufficient`() {
        val request = OrdersRequest(productId1, 1)

        every { inventoryReservationService.reserve(productId1.toString(), any(), any()) } returns null

        val exception = assertThrows<InsufficientInventoryException> {
            ordersService.orders(listOf(request), UUID.randomUUID())
        }

        assertTrue(exception.message!!.contains("재고 부족"))
        verify(exactly = 0) { outboxEventService.record(any()) }
        verify(exactly = 0) { inventoryReservationService.release(any(), any()) }
    }

    @Test
    @DisplayName("배치 주문 일부가 실패하면 앞서 예약한 재고를 해제하고 전체 실패한다")
    fun `should release previous reservations when batch order partially fails`() {
        val firstRequest = OrdersRequest(productId1, 1)
        val secondRequest = OrdersRequest(productId2, 1)
        val reservationId = UUID.randomUUID()

        every { inventoryReservationService.reserve(productId1.toString(), any(), any()) } returns reservationId
        every { inventoryReservationService.reserve(productId2.toString(), any(), any()) } returns null
        every { inventoryReservationService.release(productId1.toString(), reservationId) } just Runs

        assertThrows<InsufficientInventoryException> {
            ordersService.orders(listOf(firstRequest, secondRequest), UUID.randomUUID())
        }

        verify(exactly = 1) { inventoryReservationService.release(productId1.toString(), reservationId) }
        verify(exactly = 0) { outboxEventService.record(any()) }
    }

    @Test
    @DisplayName("여러 주문이 모두 성공하면 모든 주문 ID와 이벤트를 반환한다")
    fun `should return all order IDs when multiple orders succeed`() {
        val requests = listOf(
            OrdersRequest(productId1, 1),
            OrdersRequest(productId2, 2),
            OrdersRequest(productId3, 3)
        )
        val userId = UUID.randomUUID()

        every { inventoryReservationService.reserve(any(), any(), any()) } returns UUID.randomUUID()

        val result = ordersService.orders(requests, userId)

        assertEquals(3, result.size)
        verify(exactly = 1) { outboxEventService.record(match { it.size == 3 }) }
        verify(exactly = 1) { inventoryReservationService.reserve(productId1.toString(), any(), 600L) }
        verify(exactly = 1) { inventoryReservationService.reserve(productId2.toString(), any(), 600L) }
        verify(exactly = 1) { inventoryReservationService.reserve(productId3.toString(), any(), 600L) }
    }

    @Test
    @DisplayName("사용자 취소는 OrderCancelService에 위임한다")
    fun `should delegate cancel order`() {
        val orderId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val order = Orders(
            userId = userId, productId = productId1, sellerId = sellerId,
            quantity = 1, totalAmount = 10000L, status = OrdersStatus.ORDER_RESERVED
        )
        order.id = orderId

        every { ordersRepository.findById(orderId) } returns Optional.of(order)
        every { orderCancelService.cancel(orderId, "사용자 요청") } returns true

        val result = ordersService.cancelOrder(orderId, userId)

        assertTrue(result)
        verify(exactly = 1) { orderCancelService.cancel(orderId, "사용자 요청") }
    }

    private fun assignPersistenceFields(order: Orders) {
        if (!isIdInitialized(order)) order.id = UUID.randomUUID()
        if (!isCreatedAtInitialized(order)) order.createdAt = Instant.now()
        if (!isUpdatedAtInitialized(order)) order.updatedAt = Instant.now()
        if (order.status != OrdersStatus.ORDER_RESERVED) order.status = OrdersStatus.ORDER_RESERVED
    }

    private fun isIdInitialized(order: Orders) = try { order.id; true } catch (e: UninitializedPropertyAccessException) { false }
    private fun isCreatedAtInitialized(order: Orders) = try { order.createdAt; true } catch (e: UninitializedPropertyAccessException) { false }
    private fun isUpdatedAtInitialized(order: Orders) = try { order.updatedAt; true } catch (e: UninitializedPropertyAccessException) { false }
}
