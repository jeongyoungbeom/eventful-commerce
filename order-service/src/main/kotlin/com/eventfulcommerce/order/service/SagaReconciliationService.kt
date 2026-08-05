package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.OrderCanceledItemPayload
import com.eventfulcommerce.common.OrderCanceledPayload
import com.eventfulcommerce.common.OrderCanceledSellerPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.OrderSagaStatus
import com.eventfulcommerce.order.domain.entity.SellerOrderStatus
import com.eventfulcommerce.order.repository.OrderSagaRepository
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class SagaReconciliationService(
    private val orderSagaRepository: OrderSagaRepository,
    private val ordersRepository: OrdersRepository,
    private val orderCancelService: OrderCancelService,
    private val orderSagaService: OrderSagaService,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper
) {
    @Transactional(readOnly = true)
    fun findStaleOrderIds(staleBefore: Instant, batchSize: Int): List<UUID> =
        orderSagaRepository.findReconciliationTargets(
            RECONCILIATION_STATUSES,
            staleBefore,
            PageRequest.of(0, batchSize.coerceIn(1, MAX_BATCH_SIZE))
        ).map { it.orderId }

    /**
     * Re-emits a reconciliation command, not a new refund decision. Payment service uses its
     * refund unique key and may only re-publish the existing PAYMENT_REFUNDED fact.
     */
    @Transactional
    fun reconcile(orderId: UUID) {
        val saga = orderSagaRepository.findByOrderIdForUpdate(orderId) ?: return
        if (saga.status !in RECONCILIATION_STATUSES) return
        val order = ordersRepository.findById(orderId).orElseThrow {
            IllegalStateException("Order not found while reconciling saga: orderId=$orderId")
        }

        if (saga.status == OrderSagaStatus.COMPENSATION_REQUESTED ||
            saga.status == OrderSagaStatus.COMPENSATION_FAILED
        ) {
            when (orderCancelService.cancelForEvent(orderId, RECONCILIATION_REASON)) {
                OrderCancellationOutcome.CANCELED,
                OrderCancellationOutcome.ALREADY_CANCELED -> Unit
                OrderCancellationOutcome.LOCK_UNAVAILABLE -> throw IllegalStateException(
                    "Order cancellation lock is unavailable during saga reconciliation: orderId=$orderId"
                )
            }
        }

        val canceledSellerOrders = order.sellerOrders.filter { it.status == SellerOrderStatus.CANCELED }
        if (canceledSellerOrders.isEmpty()) {
            orderSagaService.recordReconciliationFailure(
                orderId,
                IllegalStateException("No canceled seller order exists for compensating saga")
            )
            return
        }

        orderSagaService.ensureRefundPending(orderId, canceledSellerOrders.size)
        val payload = OrderCanceledPayload(
            orderId = order.id,
            userId = order.userId,
            reason = RECONCILIATION_REASON,
            canceledSellerOrders = canceledSellerOrders.map { sellerOrder ->
                OrderCanceledSellerPayload(
                    sellerOrderId = sellerOrder.id,
                    sellerId = sellerOrder.sellerId,
                    refundAmount = sellerOrder.paymentAmount,
                    items = sellerOrder.items.map { item ->
                        OrderCanceledItemPayload(
                            orderItemId = item.id,
                            productId = item.productId,
                            quantity = item.quantity,
                            amount = item.totalAmount
                        )
                    }
                )
            }
        )
        outboxEventService.record(
            listOf(
                OutboxEvent(
                    aggregateType = OrdersStatus.ORDER.toString(),
                    aggregateId = order.id,
                    eventType = RECONCILIATION_EVENT_TYPE,
                    payload = objectMapper.writeValueAsString(payload),
                    status = OutboxStatus.PENDING
                )
            )
        )
        orderSagaService.recordReconciliationAttempt(orderId)
    }

    private companion object {
        const val MAX_BATCH_SIZE = 200
        const val RECONCILIATION_REASON = "SAGA_RECONCILIATION"
        const val RECONCILIATION_EVENT_TYPE = "ORDER_CANCELLATION_RECONCILIATION_REQUESTED"
        val RECONCILIATION_STATUSES = setOf(
            OrderSagaStatus.COMPENSATION_REQUESTED,
            OrderSagaStatus.REFUND_PENDING,
            OrderSagaStatus.COMPENSATION_FAILED
        )
    }
}
