package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.SellerOrder
import com.eventfulcommerce.order.domain.entity.CancellationStatus
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.domain.entity.SellerOrderStatus
import com.eventfulcommerce.order.domain.entity.OrderItemStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class OrderCancelExecutor(
    private val ordersRepository: OrdersRepository,
    private val inventoryReservationService: InventoryReservationService,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper
) {

    @Transactional(rollbackFor = [Exception::class])
    fun execute(orderId: UUID, reason: String): Boolean =
        request(orderId, null, reason) == OrderCancellationOutcome.CANCELED

    @Transactional(rollbackFor = [Exception::class])
    fun executeSellerOrder(orderId: UUID, sellerOrderId: UUID, reason: String): Boolean =
        request(orderId, sellerOrderId, reason) == OrderCancellationOutcome.CANCELED

    @Transactional(rollbackFor = [Exception::class])
    fun request(orderId: UUID, sellerOrderId: UUID?, reason: String): OrderCancellationOutcome {
        val order = ordersRepository.findByIdForUpdate(orderId)
            ?: return OrderCancellationOutcome.ALREADY_CANCELED
        val targets = order.sellerOrders.filter { sellerOrderId == null || it.id == sellerOrderId }
        if (targets.isEmpty()) return OrderCancellationOutcome.REJECTED
        val immediate = targets.filter { it.status == SellerOrderStatus.RESERVED }
        if (immediate.isNotEmpty()) cancelAndRecord(order, immediate, reason)
        targets.filter { it.status == SellerOrderStatus.CONFIRMED }.forEach { seller ->
            if (seller.cancellationStatus == CancellationStatus.NONE) {
                seller.cancellationRequestId = UUID.randomUUID()
                seller.cancellationReason = reason
                seller.cancellationStatus = CancellationStatus.PENDING
                recordShippingCancellation(order, seller)
            }
        }
        return when {
            targets.any { it.cancellationStatus == CancellationStatus.PENDING } ->
                OrderCancellationOutcome.PENDING
            targets.any { it.cancellationStatus == CancellationStatus.REJECTED } ->
                OrderCancellationOutcome.REJECTED
            immediate.isNotEmpty() -> OrderCancellationOutcome.CANCELED
            else -> OrderCancellationOutcome.ALREADY_CANCELED
        }
    }

    // 아직 ORDER_CONFIRMED를 기록하지 않은 결제 트랜잭션 내부 보상 전용이다.
    @Transactional(rollbackFor = [Exception::class])
    fun executeInventoryCompensation(orderId: UUID, reason: String): Boolean {
        val order = ordersRepository.findByIdForUpdate(orderId) ?: return false
        check(order.expiresAt != null && order.status in listOf(
            OrdersStatus.ORDER_RESERVED, OrdersStatus.ORDER_PARTIALLY_CANCELED)) {
            "Published confirmed orders require shipping cancellation approval"
        }
        val targets = order.sellerOrders.filter { it.status != SellerOrderStatus.CANCELED }
        if (targets.isEmpty()) return false
        cancelAndRecord(order, targets, reason)
        return true
    }

    @Transactional(rollbackFor = [Exception::class])
    fun completeShippingCancellation(payload: ShippingCancellationDecidedPayload): String? {
        val order = ordersRepository.findByIdForUpdate(payload.orderId)
            ?: error("Cancellation order not found: ${payload.orderId}")
        val seller = order.sellerOrders.single { it.id == payload.sellerOrderId }
        check(order.userId == payload.userId && seller.cancellationRequestId == payload.requestId) {
            "Shipping cancellation response does not match the request"
        }
        if (seller.cancellationStatus != CancellationStatus.PENDING) {
            check((seller.cancellationStatus == CancellationStatus.APPROVED) == payload.approved) {
                "Conflicting shipping cancellation decision"
            }
            return null
        }
        if (!payload.approved) {
            seller.cancellationStatus = CancellationStatus.REJECTED
            return null
        }
        check(seller.status == SellerOrderStatus.CONFIRMED)
        val reason = requireNotNull(seller.cancellationReason)
        cancelAndRecord(order, listOf(seller), reason)
        seller.cancellationStatus = CancellationStatus.APPROVED
        return reason
    }

    @Transactional(rollbackFor = [Exception::class])
    fun recoverPending(orderId: UUID, staleBefore: Instant) {
        val order = ordersRepository.findByIdForUpdate(orderId) ?: return
        order.sellerOrders.filter {
            it.cancellationStatus == CancellationStatus.PENDING &&
                it.cancellationRequestedAt?.isAfter(staleBefore) == false
        }.forEach { recordShippingCancellation(order, it) }
    }

    private fun recordShippingCancellation(order: Orders, seller: SellerOrder) {
        seller.cancellationRequestedAt = Instant.now()
        outboxEventService.record(listOf(OutboxEvent(
            aggregateType = "ORDER", aggregateId = order.id,
            eventType = "SHIPPING_CANCELLATION_REQUESTED",
            payload = objectMapper.writeValueAsString(ShippingCancellationRequestedPayload(
                requireNotNull(seller.cancellationRequestId), order.id, seller.id, order.userId,
                requireNotNull(seller.cancellationReason))),
            status = OutboxStatus.PENDING
        )))
    }

    @Transactional(rollbackFor = [Exception::class])
    fun executeExpired(orderId: UUID): Boolean {
        val order = ordersRepository.findByIdForUpdate(orderId) ?: return false
        // 후보 조회 이후 상태나 만료 시각이 바뀔 수 있으므로 행 잠금 안에서 다시 판단한다.
        if (order.status !in listOf(OrdersStatus.ORDER_RESERVED, OrdersStatus.ORDER_PARTIALLY_CANCELED)) return false
        val expiresAt = order.expiresAt ?: return false
        if (expiresAt.isAfter(Instant.now())) return false
        val targets = order.sellerOrders.filter { it.status == SellerOrderStatus.RESERVED }
        if (targets.isEmpty()) return false

        cancelAndRecord(order, targets, "주문 만료")
        return true
    }

    private fun cancelAndRecord(order: Orders, targets: List<SellerOrder>, reason: String) {
        cancelTargets(order.id, targets, reason)
        order.recomputeStatus()
        if (order.sellerOrders.none { it.status == SellerOrderStatus.RESERVED }) order.expiresAt = null
        ordersRepository.save(order)
        recordCanceled(order.id, order.userId, targets, reason)
    }

    private fun cancelTargets(orderId: UUID, targets: List<SellerOrder>, reason: String) {
        targets.forEach { sellerOrder ->
            sellerOrder.items.forEach { item ->
                when (item.status) {
                    OrderItemStatus.RESERVED -> {
                        val released = inventoryReservationService.release(
                            item.productId.toString(),
                            item.reservationId,
                            item.quantity
                        )
                        // DB 롤백 뒤에도 Redis commit/restock은 남는다. 종료 상태를 Lua에서
                        // 검증하며 복원하고 이미 복원된 재고는 다시 증가시키지 않는다.
                        val (result, adjustmentReason) = if (released == InventoryReservationActionResult.TERMINAL_CONFLICT) {
                            inventoryReservationService.restock(
                                item.productId.toString(), item.reservationId, item.quantity
                            ) to "RESTOCKED"
                        } else released to "RELEASED"
                        check(result.successful) {
                            "Inventory cancellation failed; order cancellation must be retried: " +
                                "orderId=$orderId, productId=${item.productId}, " +
                                "reservationId=${item.reservationId}, result=$result"
                        }
                        // DB가 아직 미취소라면 ALREADY_APPLIED도 이전 Outbox가 롤백된 복구다.
                        recordInventoryStockAdjusted(orderId, item, adjustmentReason)
                    }
                    OrderItemStatus.CONFIRMED -> {
                        val result = inventoryReservationService.restock(
                            item.productId.toString(),
                            item.reservationId,
                            item.quantity
                        )
                        check(result.successful) {
                            "Inventory restock failed; confirmed order cancellation must be retried: " +
                                "orderId=$orderId, productId=${item.productId}, " +
                                "reservationId=${item.reservationId}, result=$result"
                        }
                        recordInventoryStockAdjusted(orderId, item, "RESTOCKED")
                    }
                    OrderItemStatus.CANCELED -> Unit
                }
            }
            sellerOrder.cancel()
        }
        logger.info { "취소 대상 처리 완료: orderId=$orderId, count=${targets.size}, reason=$reason" }
    }

    private fun recordCanceled(orderId: UUID, userId: UUID, targets: List<SellerOrder>, reason: String) {
        val payload = OrderCanceledPayload(
            orderId = orderId,
            userId = userId,
            reason = reason,
            canceledSellerOrders = targets.map { sellerOrder ->
                OrderCanceledSellerPayload(
                    sellerOrderId = sellerOrder.id,
                    sellerId = sellerOrder.sellerId,
                    refundAmount = sellerOrder.paymentAmount,
                    items = sellerOrder.items.map {
                        OrderCanceledItemPayload(
                            orderItemId = it.id,
                            productId = it.productId,
                            quantity = it.quantity,
                            amount = it.totalAmount
                        )
                    }
                )
            }
        )

        outboxEventService.record(
            listOf(
                OutboxEvent(
                    aggregateType = OrdersStatus.ORDER_CANCELED.toString(),
                    aggregateId = orderId,
                    eventType = "ORDER_CANCELED",
                    payload = objectMapper.writeValueAsString(payload),
                    status = OutboxStatus.PENDING
                )
            )
        )
    }

    private fun recordInventoryStockAdjusted(
        orderId: UUID,
        item: com.eventfulcommerce.order.domain.entity.OrderItem,
        reason: String
    ) {
        outboxEventService.record(
            listOf(
                OutboxEvent(
                    aggregateType = "INVENTORY",
                    aggregateId = item.productId,
                    eventType = "INVENTORY_STOCK_ADJUSTED",
                    payload = objectMapper.writeValueAsString(
                        InventoryStockAdjustedPayload(
                            orderId = orderId,
                            productId = item.productId,
                            reservationId = item.reservationId,
                            stockDelta = item.quantity,
                            reason = reason
                        )
                    ),
                    status = OutboxStatus.PENDING
                )
            )
        )
    }
}
