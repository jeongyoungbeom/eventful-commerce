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
    @Transactional
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

    @Transactional
    fun handleOrderCanceled(message: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(message.eventId) {
            val payload = objectMapper.readValue(message.payload, OrderCanceledPayload::class.java)
            val payment = paymentRepository.findByOrderId(payload.orderId)
                ?: throw IllegalStateException("Payment prerequisite not found for canceled order: orderId=${payload.orderId}")

            when (payment.status) {
                PaymentStatus.PAYMENT_RESERVED -> {
                    payment.cancellationRequested = true
                    payment.pendingCancellationPayload = objectMapper.writeValueAsString(payload)
                    paymentRepository.save(payment)
                    businessMetrics.increment("payment.refund", "pending_payment_completion")
                }
                PaymentStatus.PAYMENT_COMPLETED,
                PaymentStatus.PAYMENT_PARTIALLY_REFUNDED -> refundCanceledOrder(payment, payload)
                else -> businessMetrics.increment("payment.refund", "not_required")
            }
        }
    }

    @Transactional
    fun handleOrderCancellationReconciliation(message: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(message.eventId) {
            val payload = objectMapper.readValue(message.payload, OrderCanceledPayload::class.java)
            val payment = paymentRepository.findByOrderId(payload.orderId)
                ?: throw IllegalStateException(
                    "Payment prerequisite not found for cancellation reconciliation: orderId=${payload.orderId}"
                )

            when (payment.status) {
                PaymentStatus.PAYMENT_RESERVED -> deferCancellation(payment, payload)
                PaymentStatus.PAYMENT_COMPLETED,
                PaymentStatus.PAYMENT_PARTIALLY_REFUNDED -> {
                    val newlyCreatedRefunds = refundCanceledOrder(payment, payload)
                    rePublishExistingRefunds(payment, payload, newlyCreatedRefunds.map { it.sellerOrderId }.toSet())
                }
                PaymentStatus.PAYMENT_REFUNDED -> rePublishExistingRefunds(payment, payload, emptySet())
                else -> businessMetrics.increment("payment.refund", "reconciliation_not_required")
            }
        }
    }

    fun refundPendingCancellation(payment: Payment) {
        val pendingPayload = payment.pendingCancellationPayload ?: return
        if (!payment.cancellationRequested) return
        check(payment.status == PaymentStatus.PAYMENT_COMPLETED || payment.status == PaymentStatus.PAYMENT_PARTIALLY_REFUNDED) {
            "Pending cancellation cannot be refunded before payment completion: paymentId=${payment.id}"
        }
        refundCanceledOrder(payment, objectMapper.readValue(pendingPayload, OrderCanceledPayload::class.java))
        payment.pendingCancellationPayload = null
        payment.cancellationRequested = false
    }

    private fun deferCancellation(payment: Payment, payload: OrderCanceledPayload) {
        payment.cancellationRequested = true
        payment.pendingCancellationPayload = objectMapper.writeValueAsString(payload)
        paymentRepository.save(payment)
        businessMetrics.increment("payment.refund", "pending_payment_completion")
    }

    private fun refundCanceledOrder(payment: Payment, payload: OrderCanceledPayload): List<PaymentRefund> {
        val createdRefunds = payload.canceledSellerOrders.mapNotNull { canceled ->
            if (paymentRefundRepository.findByPaymentIdAndSellerOrderId(payment.id, canceled.sellerOrderId) != null) {
                return@mapNotNull null
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

        payment.status = if (payment.refundedAmount >= payment.amount) {
            PaymentStatus.PAYMENT_REFUNDED
        } else {
            PaymentStatus.PAYMENT_PARTIALLY_REFUNDED
        }
        paymentRepository.save(payment)
        publishRefundEvents(payment, createdRefunds)
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
