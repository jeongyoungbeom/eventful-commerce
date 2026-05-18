package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.BaseTimeEntity
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.OrderItem
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.domain.entity.SellerOrder
import com.eventfulcommerce.order.domain.entity.SellerOrderStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.eventfulcommerce.order.repository.SellerOrderRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

class OrderCancelExecutorTest {
    private lateinit var ordersRepository: OrdersRepository
    private lateinit var sellerOrderRepository: SellerOrderRepository
    private lateinit var inventoryReservationService: InventoryReservationService
    private lateinit var outboxEventService: OutboxEventService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var orderCancelExecutor: OrderCancelExecutor

    @BeforeEach
    fun setUp() {
        ordersRepository = mockk()
        sellerOrderRepository = mockk()
        inventoryReservationService = mockk()
        outboxEventService = mockk()
        objectMapper = jacksonObjectMapper()

        every { ordersRepository.save(any<Orders>()) } answers { firstArg() }
        every { inventoryReservationService.release(any<String>(), any<UUID>(), any<Int>()) } just Runs
        every { inventoryReservationService.adjustStock(any<String>(), any<Int>()) } just Runs
        every { outboxEventService.record(any<List<OutboxEvent>>()) } just Runs

        orderCancelExecutor = OrderCancelExecutor(
            ordersRepository = ordersRepository,
            sellerOrderRepository = sellerOrderRepository,
            inventoryReservationService = inventoryReservationService,
            outboxEventService = outboxEventService,
            objectMapper = objectMapper
        )
    }

    @Test
    fun `예약 주문을 취소하면 예약 재고를 해제하고 취소 이벤트를 기록한다`() {
        val order = orderFixture(sellerOrderStatuses = listOf(SellerOrderStatus.RESERVED))
        val sellerOrder = order.sellerOrders.single()
        val item = sellerOrder.items.single()

        every { ordersRepository.findById(order.id) } returns Optional.of(order)

        val canceled = orderCancelExecutor.execute(order.id, "사용자 요청")

        assertTrue(canceled)
        assertEquals(OrdersStatus.ORDER_CANCELED, order.status)
        assertEquals(SellerOrderStatus.CANCELED, sellerOrder.status)
        verify(exactly = 1) {
            inventoryReservationService.release(
                item.productId.toString(),
                item.reservationId,
                item.quantity
            )
        }
        verify(exactly = 0) { inventoryReservationService.adjustStock(any<String>(), any<Int>()) }
        verify(exactly = 1) { ordersRepository.save(order) }
        verify(exactly = 1) {
            outboxEventService.record(
                match<List<OutboxEvent>> {
                    it.size == 1 &&
                        it.single().aggregateId == order.id &&
                        it.single().eventType == "ORDER_CANCELED" &&
                        it.single().status == OutboxStatus.PENDING
                }
            )
        }
    }

    @Test
    fun `확정 주문을 취소하면 재고를 보정하고 취소 이벤트를 기록한다`() {
        val order = orderFixture(sellerOrderStatuses = listOf(SellerOrderStatus.CONFIRMED))
        val sellerOrder = order.sellerOrders.single()
        val item = sellerOrder.items.single()

        every { ordersRepository.findById(order.id) } returns Optional.of(order)

        val canceled = orderCancelExecutor.execute(order.id, "확정 후 취소")

        assertTrue(canceled)
        assertEquals(OrdersStatus.ORDER_CANCELED, order.status)
        assertEquals(SellerOrderStatus.CANCELED, sellerOrder.status)
        verify(exactly = 0) {
            inventoryReservationService.release(any<String>(), any<UUID>(), any<Int>())
        }
        verify(exactly = 1) {
            inventoryReservationService.adjustStock(item.productId.toString(), item.quantity)
        }
        verify(exactly = 1) { ordersRepository.save(order) }
        verify(exactly = 1) { outboxEventService.record(any<List<OutboxEvent>>()) }
    }

    @Test
    fun `이미 취소된 주문은 재고 처리와 이벤트 기록을 다시 수행하지 않는다`() {
        val order = orderFixture(sellerOrderStatuses = listOf(SellerOrderStatus.CANCELED)).also {
            it.status = OrdersStatus.ORDER_CANCELED
        }

        every { ordersRepository.findById(order.id) } returns Optional.of(order)

        val canceled = orderCancelExecutor.execute(order.id, "중복 취소")

        assertFalse(canceled)
        verify(exactly = 0) {
            inventoryReservationService.release(any<String>(), any<UUID>(), any<Int>())
        }
        verify(exactly = 0) { inventoryReservationService.adjustStock(any<String>(), any<Int>()) }
        verify(exactly = 0) { ordersRepository.save(any<Orders>()) }
        verify(exactly = 0) { outboxEventService.record(any<List<OutboxEvent>>()) }
    }

    @Test
    fun `판매자 주문 하나만 취소하면 대상만 취소하고 주문 상태를 부분 취소로 바꾼다`() {
        val order = orderFixture(
            sellerOrderStatuses = listOf(SellerOrderStatus.RESERVED, SellerOrderStatus.RESERVED)
        )
        val targetSellerOrder = order.sellerOrders.first()
        val keptSellerOrder = order.sellerOrders.last()
        val targetItem = targetSellerOrder.items.single()

        every { ordersRepository.findById(order.id) } returns Optional.of(order)
        every { sellerOrderRepository.findById(targetSellerOrder.id) } returns Optional.of(targetSellerOrder)

        val canceled = orderCancelExecutor.executeSellerOrder(
            orderId = order.id,
            sellerOrderId = targetSellerOrder.id,
            reason = "부분 취소"
        )

        assertTrue(canceled)
        assertEquals(OrdersStatus.ORDER_PARTIALLY_CANCELED, order.status)
        assertEquals(SellerOrderStatus.CANCELED, targetSellerOrder.status)
        assertEquals(SellerOrderStatus.RESERVED, keptSellerOrder.status)
        verify(exactly = 1) {
            inventoryReservationService.release(
                targetItem.productId.toString(),
                targetItem.reservationId,
                targetItem.quantity
            )
        }
        verify(exactly = 1) { ordersRepository.save(order) }
        verify(exactly = 1) {
            outboxEventService.record(
                match<List<OutboxEvent>> {
                    it.size == 1 &&
                        it.single().aggregateId == order.id &&
                        it.single().eventType == "ORDER_CANCELED"
                }
            )
        }
    }

    private fun orderFixture(sellerOrderStatuses: List<SellerOrderStatus>): Orders {
        val order = Orders(
            userId = UUID.randomUUID(),
            status = OrdersStatus.ORDER_RESERVED,
            expiresAt = Instant.now().plusSeconds(600)
        )
        setIfUninitialized(order, "id", UUID.randomUUID())
        setAuditFields(order)

        sellerOrderStatuses.forEachIndexed { index, status ->
            val sellerOrder = SellerOrder(
                order = order,
                sellerId = UUID.randomUUID(),
                itemTotalAmount = 10_000L * (index + 1),
                deliveryFee = 0,
                paymentAmount = 10_000L * (index + 1),
                commissionRate = 0.1,
                commissionAmount = 1_000L * (index + 1),
                settlementAmount = 9_000L * (index + 1),
                status = status
            )
            setIfUninitialized(sellerOrder, "id", UUID.randomUUID())
            setAuditFields(sellerOrder)

            val item = OrderItem(
                sellerOrder = sellerOrder,
                productId = UUID.randomUUID(),
                productName = "취소 테스트 상품 ${index + 1}",
                quantity = index + 1,
                unitPrice = 10_000,
                totalAmount = 10_000L * (index + 1),
                reservationId = UUID.randomUUID()
            )
            setIfUninitialized(item, "id", UUID.randomUUID())
            setAuditFields(item)
            sellerOrder.addItem(item)
            if (status == SellerOrderStatus.CONFIRMED) {
                sellerOrder.confirm()
            }
            if (status == SellerOrderStatus.CANCELED) {
                sellerOrder.cancel()
            }
            order.addSellerOrder(sellerOrder)
        }

        order.recomputeTotals()
        order.recomputeStatus()
        return order
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
