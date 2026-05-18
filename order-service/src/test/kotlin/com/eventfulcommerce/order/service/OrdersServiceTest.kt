package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.order.domain.OrderItemRequest
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.domain.entity.ProductReadModel
import com.eventfulcommerce.order.domain.entity.SellerOrderStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.eventfulcommerce.order.repository.ProductReadModelRepository
import com.eventfulcommerce.order.repository.SellerOrderRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class OrdersServiceTest {
    private lateinit var ordersRepository: OrdersRepository
    private lateinit var sellerOrderRepository: SellerOrderRepository
    private lateinit var productReadModelRepository: ProductReadModelRepository
    private lateinit var outboxEventService: OutboxEventService
    private lateinit var inventoryReservationService: InventoryReservationService
    private lateinit var idempotencyHandler: IdempotencyHandler
    private lateinit var orderCancelService: OrderCancelService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var ordersService: OrdersService

    @BeforeEach
    fun setUp() {
        ordersRepository = mockk()
        sellerOrderRepository = mockk(relaxed = true)
        productReadModelRepository = mockk()
        outboxEventService = mockk()
        inventoryReservationService = mockk()
        idempotencyHandler = mockk(relaxed = true)
        orderCancelService = mockk(relaxed = true)
        objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

        every { ordersRepository.save(any<Orders>()) } answers {
            firstArg<Orders>().also { assignGeneratedFields(it) }
        }
        every { ordersRepository.delete(any<Orders>()) } just Runs
        every { outboxEventService.record(any<List<OutboxEvent>>()) } just Runs
        every<IdempotencyResult<Unit>> {
            idempotencyHandler.executeIdempotent(any<UUID>(), any<() -> Unit>())
        } answers {
            secondArg<() -> Unit>().invoke()
            IdempotencyResult.Success(Unit)
        }

        ordersService = OrdersService(
            ordersRepository = ordersRepository,
            sellerOrderRepository = sellerOrderRepository,
            productReadModelRepository = productReadModelRepository,
            outboxEventService = outboxEventService,
            inventoryReservationService = inventoryReservationService,
            idempotencyHandler = idempotencyHandler,
            objectMapper = objectMapper,
            orderCancelService = orderCancelService,
            commissionRate = 0.1
        )
    }

    @Test
    fun `재고 예약이 성공하면 주문과 아웃박스 이벤트를 생성한다`() {
        val userId = UUID.randomUUID()
        val sellerId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val product = productReadModel(productId, sellerId, price = 10_000)
        every { productReadModelRepository.findById(productId) } returns Optional.of(product)
        every<UUID?> {
            inventoryReservationService.reserve(productId.toString(), any<UUID>(), 2, any<Long>())
        } returns reservationId

        val response = ordersService.orders(
            OrdersRequest(items = listOf(OrderItemRequest(productId = productId, quantity = 2))),
            userId
        )

        assertNotNull(response.orderId)
        assertEquals(OrdersStatus.ORDER_RESERVED, response.status)
        assertEquals(20_000, response.totalItemAmount)
        assertEquals(20_000, response.totalPaymentAmount)
        assertEquals(2_000, response.totalCommissionAmount)
        assertEquals(18_000, response.totalSettlementAmount)
        assertTrue(response.failedItems.isEmpty())
        assertEquals(1, response.sellerOrders.size)
        assertEquals(1, response.sellerOrders.single().items.size)
        assertEquals(reservationId, response.sellerOrders.single().items.single().reservationId)

        verify(exactly = 1) {
            outboxEventService.record(
                match<List<OutboxEvent>> {
                    it.size == 1 &&
                        it.single().eventType == OrdersStatus.ORDER_RESERVED.toString() &&
                        it.single().status == OutboxStatus.PENDING
                }
            )
        }
        verify(exactly = 0) { ordersRepository.delete(any<Orders>()) }
    }

    @Test
    fun `일부 상품 예약이 실패해도 성공 상품만 주문하고 실패 상품을 응답한다`() {
        val userId = UUID.randomUUID()
        val sellerId = UUID.randomUUID()
        val successProductId = UUID.randomUUID()
        val failedProductId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()

        every { productReadModelRepository.findById(successProductId) } returns Optional.of(
            productReadModel(successProductId, sellerId, price = 12_000)
        )
        every { productReadModelRepository.findById(failedProductId) } returns Optional.of(
            productReadModel(failedProductId, sellerId, price = 8_000)
        )
        every<UUID?> {
            inventoryReservationService.reserve(successProductId.toString(), any<UUID>(), 1, any<Long>())
        } returns reservationId
        every<UUID?> {
            inventoryReservationService.reserve(failedProductId.toString(), any<UUID>(), 3, any<Long>())
        } returns null
        every { inventoryReservationService.getAvailableStock(failedProductId.toString()) } returns 1L

        val response = ordersService.orders(
            OrdersRequest(
                items = listOf(
                    OrderItemRequest(productId = successProductId, quantity = 1),
                    OrderItemRequest(productId = failedProductId, quantity = 3)
                )
            ),
            userId
        )

        assertNotNull(response.orderId)
        assertEquals(OrdersStatus.ORDER_RESERVED, response.status)
        assertEquals(12_000, response.totalItemAmount)
        assertEquals(1, response.sellerOrders.single().items.size)
        assertEquals(successProductId, response.sellerOrders.single().items.single().productId)
        assertEquals(1, response.failedItems.size)
        assertEquals(failedProductId, response.failedItems.single().productId)
        assertEquals("INSUFFICIENT_STOCK", response.failedItems.single().reason)
        assertEquals(3, response.failedItems.single().requestedQuantity)
        assertEquals(1, response.failedItems.single().availableQuantity)

        verify(exactly = 1) { outboxEventService.record(any<List<OutboxEvent>>()) }
        verify(exactly = 0) { ordersRepository.delete(any<Orders>()) }
    }

    @Test
    fun `모든 상품 예약이 실패하면 임시 주문을 삭제하고 실패 응답을 반환한다`() {
        val userId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val sellerId = UUID.randomUUID()

        every { productReadModelRepository.findById(productId) } returns Optional.of(
            productReadModel(productId, sellerId, price = 15_000)
        )
        every<UUID?> {
            inventoryReservationService.reserve(productId.toString(), any<UUID>(), 5, any<Long>())
        } returns null
        every { inventoryReservationService.getAvailableStock(productId.toString()) } returns 0L

        val response = ordersService.orders(
            OrdersRequest(items = listOf(OrderItemRequest(productId = productId, quantity = 5))),
            userId
        )

        assertNull(response.orderId)
        assertEquals(OrdersStatus.ORDER_FAILED, response.status)
        assertEquals(0, response.totalPaymentAmount)
        assertTrue(response.sellerOrders.isEmpty())
        assertEquals(1, response.failedItems.size)
        assertEquals("INSUFFICIENT_STOCK", response.failedItems.single().reason)

        verify(exactly = 1) { ordersRepository.delete(any<Orders>()) }
        verify(exactly = 0) { outboxEventService.record(any<List<OutboxEvent>>()) }
    }

    @Test
    fun `판매 불가 상품은 재고 예약을 시도하지 않고 실패 상품으로 응답한다`() {
        val userId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val sellerId = UUID.randomUUID()

        every { productReadModelRepository.findById(productId) } returns Optional.of(
            productReadModel(productId, sellerId, price = 15_000, status = "INACTIVE")
        )

        val response = ordersService.orders(
            OrdersRequest(items = listOf(OrderItemRequest(productId = productId, quantity = 1))),
            userId
        )

        assertNull(response.orderId)
        assertEquals(OrdersStatus.ORDER_FAILED, response.status)
        assertEquals("PRODUCT_NOT_AVAILABLE", response.failedItems.single().reason)

        verify(exactly = 0) {
            inventoryReservationService.reserve(any<String>(), any<UUID>(), any<Int>(), any<Long>())
        }
        verify(exactly = 1) { ordersRepository.delete(any<Orders>()) }
        verify(exactly = 0) { outboxEventService.record(any<List<OutboxEvent>>()) }
    }

    @Test
    fun `결제 완료 이벤트를 처리하면 예약 재고를 확정하고 주문 확정 이벤트를 기록한다`() {
        val order = reservedOrderFixture()
        val sellerOrder = order.sellerOrders.single()
        val item = sellerOrder.items.single()
        val eventId = UUID.randomUUID()
        val paymentCompletedMessage = paymentCompletedMessage(
            eventId = eventId,
            orderId = order.id,
            userId = order.userId
        )

        every { ordersRepository.findById(order.id) } returns Optional.of(order)
        every { inventoryReservationService.commit(any<String>(), any<UUID>(), any<Int>()) } just Runs

        ordersService.handlePaymentCompleted(paymentCompletedMessage)

        assertEquals(OrdersStatus.ORDER_CONFIRMED, order.status)
        assertEquals(SellerOrderStatus.CONFIRMED, sellerOrder.status)
        verify(exactly = 1) {
            idempotencyHandler.executeIdempotent(eq(eventId), any<() -> Unit>())
        }
        verify(exactly = 1) {
            inventoryReservationService.commit(item.productId.toString(), item.reservationId, item.quantity)
        }
        verify(exactly = 1) { ordersRepository.save(order) }
        verify(exactly = 1) {
            outboxEventService.record(
                match<List<OutboxEvent>> {
                    it.size == 1 &&
                        it.single().aggregateId == order.id &&
                        it.single().eventType == OrdersStatus.ORDER_CONFIRMED.toString() &&
                        it.single().status == OutboxStatus.PENDING
                }
            )
        }
    }

    @Test
    fun `이미 확정된 주문은 결제 완료 이벤트를 다시 받아도 재고 확정과 이벤트 기록을 반복하지 않는다`() {
        val order = reservedOrderFixture().also {
            it.sellerOrders.forEach { sellerOrder -> sellerOrder.confirm() }
            it.recomputeStatus()
        }
        val eventId = UUID.randomUUID()
        val paymentCompletedMessage = paymentCompletedMessage(
            eventId = eventId,
            orderId = order.id,
            userId = order.userId
        )

        every { ordersRepository.findById(order.id) } returns Optional.of(order)

        ordersService.handlePaymentCompleted(paymentCompletedMessage)

        assertEquals(OrdersStatus.ORDER_CONFIRMED, order.status)
        verify(exactly = 1) {
            idempotencyHandler.executeIdempotent(eq(eventId), any<() -> Unit>())
        }
        verify(exactly = 0) {
            inventoryReservationService.commit(any<String>(), any<UUID>(), any<Int>())
        }
        verify(exactly = 0) { ordersRepository.save(any<Orders>()) }
        verify(exactly = 0) { outboxEventService.record(any<List<OutboxEvent>>()) }
    }

    private fun productReadModel(
        productId: UUID,
        sellerId: UUID,
        price: Long,
        status: String = "ACTIVE"
    ) = ProductReadModel(
        productId = productId,
        sellerId = sellerId,
        name = "테스트 상품",
        price = price,
        stock = 100,
        category = "ELECTRONICS",
        status = status
    )

    private fun reservedOrderFixture(): Orders {
        val order = Orders(
            userId = UUID.randomUUID(),
            status = OrdersStatus.ORDER_RESERVED,
            expiresAt = Instant.now().plusSeconds(600)
        )
        val sellerOrder = com.eventfulcommerce.order.domain.entity.SellerOrder(
            order = order,
            sellerId = UUID.randomUUID(),
            itemTotalAmount = 20_000,
            deliveryFee = 0,
            paymentAmount = 20_000,
            commissionRate = 0.1,
            commissionAmount = 2_000,
            settlementAmount = 18_000,
            status = SellerOrderStatus.RESERVED
        )
        val item = com.eventfulcommerce.order.domain.entity.OrderItem(
            sellerOrder = sellerOrder,
            productId = UUID.randomUUID(),
            productName = "결제 완료 테스트 상품",
            quantity = 2,
            unitPrice = 10_000,
            totalAmount = 20_000,
            reservationId = UUID.randomUUID()
        )
        sellerOrder.addItem(item)
        order.addSellerOrder(sellerOrder)
        order.recomputeTotals()

        assignGeneratedFields(order)
        return order
    }

    private fun paymentCompletedMessage(eventId: UUID, orderId: UUID, userId: UUID): OutboxEventMessage {
        val payload = PaymentCompletedPayload(
            paymentId = UUID.randomUUID(),
            orderId = orderId,
            userId = userId,
            amount = 20_000,
            completedAt = Instant.now()
        )
        return OutboxEventMessage(
            eventId = eventId,
            aggregateType = "PAYMENT",
            aggregateId = UUID.randomUUID(),
            eventType = "PAYMENT_COMPLETED",
            occurredAt = Instant.now(),
            payload = objectMapper.writeValueAsString(payload)
        )
    }

    private fun assignGeneratedFields(order: Orders) {
        setIfUninitialized(order, "id", UUID.randomUUID())
        setAuditFields(order)

        order.sellerOrders.forEach { sellerOrder ->
            setIfUninitialized(sellerOrder, "id", UUID.randomUUID())
            setAuditFields(sellerOrder)
            sellerOrder.items.forEach { item ->
                setIfUninitialized(item, "id", UUID.randomUUID())
                setAuditFields(item)
            }
        }
    }

    private fun setAuditFields(entity: BaseTimeEntity) {
        val now = Instant.now()
        setIfUninitialized(entity, "createdAt", now)
        setField(entity, "updatedAt", now)
    }

    private fun setIfUninitialized(target: Any, fieldName: String, value: Any) {
        try {
            if (findField(target, fieldName).get(target) == null) {
                setField(target, fieldName, value)
            }
        } catch (_: UninitializedPropertyAccessException) {
            setField(target, fieldName, value)
        } catch (_: NullPointerException) {
            setField(target, fieldName, value)
        }
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        findField(target, fieldName).set(target, value)
    }

    private fun findField(target: Any, fieldName: String): java.lang.reflect.Field {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(fieldName)
                field.isAccessible = true
                return field
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("Field not found: ${target.javaClass.name}.$fieldName")
    }
}
