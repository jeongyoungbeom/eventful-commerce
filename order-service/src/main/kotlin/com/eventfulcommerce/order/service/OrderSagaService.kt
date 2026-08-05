package com.eventfulcommerce.order.service

import com.eventfulcommerce.order.domain.entity.OrderSaga
import com.eventfulcommerce.order.domain.entity.OrderSagaRefundReceipt
import com.eventfulcommerce.order.domain.entity.OrderSagaStatus
import com.eventfulcommerce.order.repository.OrderSagaRefundReceiptRepository
import com.eventfulcommerce.order.repository.OrderSagaRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class OrderSagaService(
    private val orderSagaRepository: OrderSagaRepository,
    private val orderSagaRefundReceiptRepository: OrderSagaRefundReceiptRepository
) {
    fun initialize(orderId: UUID) {
        if (orderSagaRepository.findByOrderId(orderId) == null) {
            orderSagaRepository.save(OrderSaga(orderId = orderId))
        }
    }

    fun markPaymentCompleted(orderId: UUID) {
        val saga = saga(orderId)
        if (saga.status == OrderSagaStatus.RESERVED) saga.status = OrderSagaStatus.PAYMENT_COMPLETED
    }

    fun markConfirmed(orderId: UUID) {
        saga(orderId).apply {
            status = OrderSagaStatus.CONFIRMED
            lastError = null
        }
    }

    fun requestCompensation(orderId: UUID, exception: Exception) {
        saga(orderId).apply {
            status = OrderSagaStatus.COMPENSATION_REQUESTED
            compensationAttemptCount += 1
            lastError = (exception.message ?: exception.javaClass.name).take(2_000)
        }
    }

    fun markRefundPending(orderId: UUID, expectedRefundCount: Int) {
        require(expectedRefundCount > 0) { "expectedRefundCount must be greater than zero" }
        saga(orderId).apply {
            status = OrderSagaStatus.REFUND_PENDING
            // A second seller shipping failure can arrive while another refund is still pending.
            this.expectedRefundCount += expectedRefundCount
        }
    }

    fun ensureRefundPending(orderId: UUID, expectedRefundCount: Int) {
        require(expectedRefundCount > 0) { "expectedRefundCount must be greater than zero" }
        saga(orderId).apply {
            status = OrderSagaStatus.REFUND_PENDING
            // Reconciliation observes the full canceled set, so it must not count prior retries again.
            this.expectedRefundCount = maxOf(this.expectedRefundCount, expectedRefundCount)
        }
    }

    fun markRefundReceived(orderId: UUID, sellerOrderId: UUID) {
        val saga = orderSagaRepository.findByOrderId(orderId) ?: return
        if (saga.status != OrderSagaStatus.REFUND_PENDING) return
        if (orderSagaRefundReceiptRepository.existsBySagaIdAndSellerOrderId(saga.id, sellerOrderId)) return

        orderSagaRefundReceiptRepository.save(
            OrderSagaRefundReceipt(sagaId = saga.id, sellerOrderId = sellerOrderId)
        )
        saga.refundedCount = (saga.refundedCount + 1).coerceAtMost(saga.expectedRefundCount)
        if (saga.refundedCount == saga.expectedRefundCount) saga.status = OrderSagaStatus.COMPENSATED
    }

    fun recordReconciliationAttempt(orderId: UUID) {
        saga(orderId).apply {
            reconciliationAttemptCount += 1
            lastReconciledAt = Instant.now()
            lastError = "Saga reconciliation requested"
        }
    }

    fun recordReconciliationFailure(orderId: UUID, exception: Exception) {
        saga(orderId).apply {
            reconciliationAttemptCount += 1
            lastReconciledAt = Instant.now()
            lastError = (exception.message ?: exception.javaClass.name).take(2_000)
        }
    }

    fun markCompensationFailed(orderId: UUID, exception: Exception) {
        saga(orderId).apply {
            status = OrderSagaStatus.COMPENSATION_FAILED
            lastError = (exception.message ?: exception.javaClass.name).take(2_000)
        }
    }

    private fun saga(orderId: UUID): OrderSaga = orderSagaRepository.findByOrderId(orderId)
        ?: orderSagaRepository.save(OrderSaga(orderId = orderId))
}
