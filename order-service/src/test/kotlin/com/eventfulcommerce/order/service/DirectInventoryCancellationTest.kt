package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.*
import com.eventfulcommerce.order.message.DirectInventoryEventsConsumer
import com.eventfulcommerce.order.repository.*
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.*
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

class DirectInventoryCancellationTest {
    @ParameterizedTest
    @CsvSource("ALLOCATED, OUTBOUND_INELIGIBILITY_CANCELED", "CANCEL_PENDING, OUTBOUND_INELIGIBILITY_CANCELED",
        "CANCELED, OUTBOUND_INELIGIBILITY_CANCELED", "ALLOCATED, DIRECT_RETURN_INSPECTED", "CANCEL_PENDING, DIRECT_RETURN_INSPECTED")
    fun `회수와 기존 반품은 처리 순서와 중복에 관계없이 최초 비용으로 한 품목만 환불한다`(initial: AttemptItemState, type: String) {
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val now = Instant.now()
        val order = Orders(UUID.randomUUID(), 25000, 3000, 28000, status = OrdersStatus.ORDER_CONFIRMED, mixedOrder = true)
            .also { it.id = UUID.randomUUID(); it.createdAt = now; it.updatedAt = now }
        val seller = SellerOrder(order, SalesType.OFFICIAL_SELLER_ID, 25000, 3000, 28000, 0.0, 0, 0,
            SellerOrderStatus.CONFIRMED, salesType = SalesType.DIRECT_PURCHASE)
            .also { it.createdAt = now; it.updatedAt = now }
        order.addSellerOrder(seller)
        val warehouse = UUID.randomUUID()
        val items = listOf(10000L, 15000L).mapIndexed { index, price ->
            val line = OrderItem(seller, UUID.randomUUID(), "품목$index", 1, price, price, UUID.randomUUID(),
                OrderItemStatus.CONFIRMED, if (index == 0) 3000 else 0)
                .also { it.createdAt = now; it.updatedAt = now }
            seller.addItem(line)
            AttemptItem(line.id, line.reservationId, seller.id, line.productId, line.productName, seller.sellerId,
                SalesType.DIRECT_PURCHASE, 1, price, if (index == 0) initial else AttemptItemState.ALLOCATED,
                reason = if (initial == AttemptItemState.CANCELED && index == 0) "USER_CANCELED" else null,
                warehouseId = warehouse, confirmationPublished = true,
                refundRequested = initial == AttemptItemState.CANCELED && index == 0,
                cancellationRejected = initial == AttemptItemState.ALLOCATED && index == 0)
        }
        val attempt = MixedOrderAttempt(order.id, mapper.writeValueAsString(items), Instant.now().minusSeconds(1),
            3000, 100000, 0.0, paymentState = MixedPaymentState.COMPLETED)
        val orders = mock(OrdersRepository::class.java)
        val attempts = mock(MixedOrderAttemptRepository::class.java)
        `when`(orders.findByIdForUpdate(order.id)).thenReturn(order)
        `when`(attempts.findById(order.id)).thenReturn(Optional.of(attempt))
        val outbox = mock(OutboxEventService::class.java)
        val events = mutableListOf<OutboxEvent>()
        doAnswer { call -> events.addAll(call.getArgument<List<OutboxEvent>>(0)); null }.`when`(outbox).record(anyList())
        val state = MixedOrderStateService(orders, attempts, mock(OrderRequestIdempotencyRepository::class.java),
            mock(ProductReadModelRepository::class.java), mapper, outbox, Clock.systemUTC(), 0.0, 86400, 3000, 100000)
        val consumer = DirectInventoryEventsConsumer(mapper, state)
        val target = items.first()
        val payload = DirectFulfillmentPayload(order.id, order.userId, seller.id, target.orderItemId,
            target.reservationId, target.productId, warehouse, 1)
        val event = OutboxEventMessage(UUID.randomUUID(), "DIRECT_OUTBOUND", order.id,
            type, Instant.now(), mapper.writeValueAsString(payload))
        assertThrows(IllegalArgumentException::class.java) {
            consumer.receive(mapper.writeValueAsString(event.copy(payload = mapper.writeValueAsString(payload.copy(quantity = 2)))))
        }
        consumer.receive(mapper.writeValueAsString(event))
        consumer.receive(mapper.writeValueAsString(event.copy(eventId = UUID.randomUUID())))
        val snapshot = state.snapshot(order.id)
        assertEquals(AttemptItemState.CANCELED, snapshot.items.first().state)
        assertEquals(AttemptItemState.ALLOCATED, snapshot.items.last().state)
        if (initial != AttemptItemState.CANCELED) {
            assertFalse(snapshot.items.first().cancellationRejected)
            assertEquals(if (type == "DIRECT_RETURN_INSPECTED") "RETURN_INSPECTED" else "STOCK_INELIGIBLE", snapshot.items.first().reason)
        }
        assertEquals(28000, order.totalPaymentAmount); assertEquals(3000, order.totalDeliveryFee)
        assertEquals(OrderItemStatus.CONFIRMED, seller.items.last().status)
        assertEquals(if (initial == AttemptItemState.CANCELED) 0 else 1, events.size)
        if (events.isNotEmpty()) {
            val refund = mapper.readValue(events.single().payload, OrderCanceledPayload::class.java).canceledSellerOrders.single()
            assertEquals("ORDER_CANCELED", events.single().eventType)
            assertEquals(target.orderItemId, refund.orderItemId); assertEquals(13000, refund.refundAmount)
        }
    }
}
