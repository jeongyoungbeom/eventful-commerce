package com.eventfulcommerce.payment.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OrderCanceledPayload
import com.eventfulcommerce.common.OrderReservedPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.PaymentCompletedSellerPayload
import com.eventfulcommerce.common.PaymentRefundedPayload
import com.eventfulcommerce.common.metrics.EventfulBusinessMetrics
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.payment.domain.PaymentStatus
import com.eventfulcommerce.payment.domain.entity.Payment
import com.eventfulcommerce.payment.domain.entity.PaymentRefund
import com.eventfulcommerce.payment.repository.PaymentRefundRepository
import com.eventfulcommerce.payment.repository.PaymentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PaymentService(
    private val objectMapper: ObjectMapper,
    private val idempotencyHandler: IdempotencyHandler,
    private val paymentRepository: PaymentRepository,
    private val paymentRefundRepository: PaymentRefundRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val businessMetrics: EventfulBusinessMetrics
) {
    @Transactional(rollbackFor = [Exception::class])
    fun handleOrderCreated(message: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(message.eventId) {
            val payload = objectMapper.readValue(message.payload, OrderReservedPayload::class.java)
            if (paymentRepository.findByOrderId(payload.orderId) != null) return@executeIdempotent

            paymentRepository.save(
                Payment(
                    orderId = payload.orderId,
                    userId = payload.userId,
                    status = PaymentStatus.PAYMENT_RESERVED,
                    amount = payload.totalPaymentAmount,
                    sellerOrdersJson = objectMapper.writeValueAsString(
                        payload.sellerOrders.map {
                            PaymentCompletedSellerPayload(
                                sellerOrderId = it.sellerOrderId,
                                sellerId = it.sellerId,
                                paymentAmount = it.paymentAmount,
                                commissionRate = it.commissionRate,
                                commissionAmount = it.commissionAmount,
                                settlementAmount = it.settlementAmount
                            )
                        }
                    )
                )
            )
            businessMetrics.increment("payment.reserve", "created")
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    fun handleOrderCanceled(message: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(message.eventId) {
            val payload = objectMapper.readValue(message.payload, OrderCanceledPayload::class.java)
            val payment = paymentRepository.findByOrderIdForUpdate(payload.orderId)
                ?: throw IllegalStateException("Payment prerequisite not found for canceled order: orderId=${payload.orderId}")

            when (payment.status) {
                PaymentStatus.PAYMENT_RESERVED -> deferCancellation(payment, payload)
                PaymentStatus.PAYMENT_COMPLETED,
                PaymentStatus.PAYMENT_PARTIALLY_REFUNDED,
                PaymentStatus.PAYMENT_REFUNDED -> refundCanceledOrder(payment, payload)
                else -> businessMetrics.increment("payment.refund", "not_required")
            }
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    fun handleOrderCancellationReconciliation(message: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(message.eventId) {
            val payload = objectMapper.readValue(message.payload, OrderCanceledPayload::class.java)
            val payment = paymentRepository.findByOrderIdForUpdate(payload.orderId)
                ?: throw IllegalStateException(
                    "Payment prerequisite not found for cancellation reconciliation: orderId=${payload.orderId}"
                )

            when (payment.status) {
                PaymentStatus.PAYMENT_RESERVED -> deferCancellation(payment, payload)
                PaymentStatus.PAYMENT_COMPLETED,
                PaymentStatus.PAYMENT_PARTIALLY_REFUNDED,
                PaymentStatus.PAYMENT_REFUNDED -> {
                    val newlyCreatedRefunds = refundCanceledOrder(payment, payload)
                    rePublishExistingRefunds(payment, payload, newlyCreatedRefunds.map { it.sellerOrderId }.toSet())
                }
                else -> businessMetrics.increment("payment.refund", "reconciliation_not_required")
            }
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    fun refundPendingCancellation(orderId: UUID) {
        val payment = paymentRepository.findByOrderIdForUpdate(orderId)
            ?: throw IllegalStateException("Payment prerequisite not found: orderId=$orderId")
        val pending = pendingCancellations(payment)
        if (pending.isEmpty()) return
        check(payment.status == PaymentStatus.PAYMENT_COMPLETED || payment.status == PaymentStatus.PAYMENT_PARTIALLY_REFUNDED) {
            "Pending cancellation cannot be refunded before payment completion: paymentId=${payment.id}"
        }
        pending.forEach { refundCanceledOrder(payment, it) }
        payment.pendingCancellationPayload = null
        payment.cancellationRequested = false
    }

    private fun pendingCancellations(payment: Payment): List<OrderCanceledPayload> =
        payment.pendingCancellationPayload?.let {
            objectMapper.readValue(it, Array<OrderCanceledPayload>::class.java).toList()
        } ?: emptyList()

    private fun validateCancellation(payment: Payment, payload: OrderCanceledPayload) {
        require(payload.orderId == payment.orderId && payload.userId == payment.userId) {
            "Cancellation does not match payment: paymentId=${payment.id}"
        }
        check(payment.amount > 0 && payment.refundedAmount in 0..payment.amount) {
            "Invalid payment refund balance: paymentId=${payment.id}"
        }
    }

    private fun deferCancellation(payment: Payment, payload: OrderCanceledPayload) {
        validateCancellation(payment, payload)
        val pending = pendingCancellations(payment).associateBy {
            it.canceledSellerOrders.single().sellerOrderId
        }.toMutableMap()
        payload.canceledSellerOrders.forEach { canceled ->
            require(canceled.refundAmount > 0) { "Refund amount must be positive" }
            val existing = pending[canceled.sellerOrderId]?.canceledSellerOrders?.single()
            if (existing != null) {
                require(existing.sellerId == canceled.sellerId && existing.refundAmount == canceled.refundAmount) {
                    "Conflicting pending cancellation: sellerOrderId=${canceled.sellerOrderId}"
                }
            } else {
                pending[canceled.sellerOrderId] = payload.copy(canceledSellerOrders = listOf(canceled))
            }
        }
        var remaining = payment.amount - payment.refundedAmount
        pending.values.forEach {
            val amount = it.canceledSellerOrders.single().refundAmount
            require(amount > 0 && amount <= remaining) { "Pending cancellations exceed payment amount" }
            remaining -= amount
        }
        if (pending.isEmpty()) return
        payment.cancellationRequested = true
        payment.pendingCancellationPayload = objectMapper.writeValueAsString(pending.values.toList())
        paymentRepository.save(payment)
        businessMetrics.increment("payment.refund", "pending_payment_completion")
    }

    private fun refundCanceledOrder(payment: Payment, payload: OrderCanceledPayload): List<PaymentRefund> {
        validateCancellation(payment, payload)
        val createdRefunds = payload.canceledSellerOrders.mapNotNull { canceled ->
            require(canceled.refundAmount > 0) { "Refund amount must be positive" }
            val existing = paymentRefundRepository.findByPaymentIdAndSellerOrderId(payment.id, canceled.sellerOrderId)
            if (existing != null) {
                require(existing.sellerId == canceled.sellerId && existing.amount == canceled.refundAmount) {
                    "Conflicting refund: sellerOrderId=${canceled.sellerOrderId}"
                }
                return@mapNotNull null
            }
            require(canceled.refundAmount <= payment.amount - payment.refundedAmount) {
                "Refund exceeds remaining payment amount: paymentId=${payment.id}"
            }
            val refund = paymentRefundRepository.save(
                PaymentRefund(
                    payment = payment,
                    orderId = payload.orderId,
                    sellerOrderId = canceled.sellerOrderId,
                    sellerId = canceled.sellerId,
                    amount = canceled.refundAmount,
                    reason = payload.reason
                )
            )
            payment.refundedAmount += refund.amount
            refund
        }

        if (createdRefunds.isNotEmpty()) {
            payment.status = if (payment.refundedAmount == payment.amount) {
                PaymentStatus.PAYMENT_REFUNDED
            } else {
                PaymentStatus.PAYMENT_PARTIALLY_REFUNDED
            }
            paymentRepository.save(payment)
            publishRefundEvents(payment, createdRefunds)
        }
        businessMetrics.increment("payment.refund", if (createdRefunds.isEmpty()) "skipped" else "created")
        return createdRefunds
    }

    private fun rePublishExistingRefunds(
        payment: Payment,
        payload: OrderCanceledPayload,
        newlyCreatedSellerOrderIds: Set<UUID>
    ) {
        val sellerOrderIds = payload.canceledSellerOrders
            .map { it.sellerOrderId }
            .filterNot { it in newlyCreatedSellerOrderIds }
        if (sellerOrderIds.isEmpty()) return

        val existingRefunds = paymentRefundRepository.findByPaymentIdAndSellerOrderIdIn(payment.id, sellerOrderIds)
        publishRefundEvents(payment, existingRefunds)
        if (existingRefunds.isNotEmpty()) {
            businessMetrics.increment("payment.refund", "reconciliation_republished")
        }
    }

    private fun publishRefundEvents(payment: Payment, refunds: List<PaymentRefund>) {
        if (refunds.isEmpty()) return
        outboxEventRepository.saveAll(
            refunds.map { refund ->
                OutboxEvent(
                    aggregateType = "PAYMENT_REFUND",
                    aggregateId = payment.id,
                    eventType = "PAYMENT_REFUNDED",
                    payload = objectMapper.writeValueAsString(
                        PaymentRefundedPayload(
                            refundId = refund.id,
                            paymentId = payment.id,
                            orderId = refund.orderId,
                            sellerOrderId = refund.sellerOrderId,
                            sellerId = refund.sellerId,
                            amount = refund.amount,
                            reason = refund.reason,
                            refundedAt = refund.refundedAt
                        )
                    ),
                    status = OutboxStatus.PENDING
                )
            }
        )
    }
}
