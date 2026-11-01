package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.*
import com.eventfulcommerce.order.dto.OrderResponse
import com.eventfulcommerce.order.repository.*
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

class MixedOrderRecoveryProgressTest {
    private fun item(state: AttemptItemState = AttemptItemState.PENDING) = AttemptItem(UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "직매입", SalesType.OFFICIAL_SELLER_ID, SalesType.DIRECT_PURCHASE, 1, 100, state)

    @Test @Timeout(45)
    fun `앞 다섯 품목이 회차 시간을 소진해도 새 서비스는 저장한 여섯 번째부터 복구한다`() {
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val now = Instant.now()
        val order = Orders(UUID.randomUUID(), status = OrdersStatus.ORDER_PROCESSING, expiresAt = now.plusSeconds(600), mixedOrder = true)
            .also { it.id = UUID.randomUUID(); it.createdAt = now; it.updatedAt = now }
        val items = List(6) { item() }
        var stored = MixedOrderAttempt(order.id, mapper.writeValueAsString(items), order.expiresAt!!, 0, Long.MAX_VALUE, 0.0)
        val orders = mock(OrdersRepository::class.java)
        val attempts = mock(MixedOrderAttemptRepository::class.java)
        `when`(orders.findByIdForUpdate(order.id)).thenReturn(order)
        `when`(attempts.findById(order.id)).thenAnswer { Optional.of(stored) }
        fun state() = MixedOrderStateService(orders, attempts, mock(OrderRequestIdempotencyRepository::class.java),
            mock(ProductReadModelRepository::class.java), mapper, mock(OutboxEventService::class.java), Clock.systemUTC(), 0.0, 86400, 0, Long.MAX_VALUE)
        val remote = mock(InventoryServiceClient::class.java)
        val calls = mutableListOf<UUID>()
        var slow = true
        `when`(remote.resolve(eq(order.id) ?: order.id, any(AttemptItem::class.java) ?: items.first(), any(Instant::class.java) ?: order.expiresAt!!)).thenAnswer { call ->
            val item = call.getArgument<AttemptItem>(1)
            calls.add(item.orderItemId)
            val index = items.indexOfFirst { it.orderItemId == item.orderItemId }
            assertEquals((index + 1) % 6, stored.recoveryCursor, "원격 호출 전에 진행 위치를 저장해야 합니다")
            if (slow && index < 5) { Thread.sleep(6100); null } else RemoteReservation("RESERVED", warehouseId = UUID.randomUUID())
        }
        fun service() = MixedOrdersService(state(), mock(OrdersService::class.java), remote, mock(InventoryReservationService::class.java))
        service().recover(order.id)
        assertEquals(items.take(5).map { it.orderItemId }, calls)
        assertEquals(5, stored.recoveryCursor)
        val originalIds = state().snapshot(order.id).items.map { it.orderItemId }
        stored = mapper.readValue(mapper.writeValueAsString(stored), MixedOrderAttempt::class.java)
        calls.clear(); slow = false
        val response = service().recover(order.id)
        assertEquals(items.last().orderItemId, calls.first())
        assertEquals(6, calls.distinct().size)
        assertEquals(originalIds, state().snapshot(order.id).items.map { it.orderItemId })
        assertEquals(OrdersStatus.ORDER_AWAITING_CONFIRMATION, response.status)
        assertEquals(6, response.sellerOrders.sumOf { it.items.size })
    }

    @ParameterizedTest
    @EnumSource(value = AttemptItemState::class, names = ["PENDING", "RESERVED", "ALLOCATE_PENDING", "CANCEL_PENDING", "RELEASE_PENDING"])
    fun `예약 할당 취소 해제 모두 저장된 위치에서 시작하고 호출 전에 진행을 기록한다`(phase: AttemptItemState) {
        val id = UUID.randomUUID()
        val expiry = Instant.now().plusSeconds(600)
        val items = List(3) { item(phase) }
        val response = mock(OrderResponse::class.java)
        `when`(response.confirmedVersion).thenReturn(null)
        val snapshot = AttemptSnapshot(id, expiry, items, response, recoveryCursor = 1)
        val state = mock(MixedOrderStateService::class.java)
        `when`(state.snapshot(id, null)).thenReturn(snapshot)
        `when`(state.completePass(id, null)).thenReturn(response)
        val remote = mock(InventoryServiceClient::class.java)
        MixedOrdersService(state, mock(OrdersService::class.java), remote, mock(InventoryReservationService::class.java)).recover(id)
        val sequence = inOrder(state, remote)
        for (item in listOf(items[1], items[2], items[0])) {
            sequence.verify(state).advanceRecovery(id, item.orderItemId)
            when (phase) {
                AttemptItemState.PENDING, AttemptItemState.RESERVED -> sequence.verify(remote).resolve(id, item, expiry)
                AttemptItemState.ALLOCATE_PENDING -> sequence.verify(remote).allocate(id, item, expiry)
                AttemptItemState.CANCEL_PENDING -> sequence.verify(remote).cancel(id, item, expiry)
                AttemptItemState.RELEASE_PENDING -> sequence.verify(remote).release(id, item, expiry)
                else -> error("테스트 대상 아님")
            }
        }
    }
}
