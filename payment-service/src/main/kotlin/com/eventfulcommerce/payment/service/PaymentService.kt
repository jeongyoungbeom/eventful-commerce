package com.eventfulcommerce.payment.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.payment.domain.PaymentStatus
import com.eventfulcommerce.payment.domain.entity.Payment
import com.eventfulcommerce.payment.domain.entity.PaymentRefund
import com.eventfulcommerce.payment.repository.PaymentRefundRepository
import com.eventfulcommerce.payment.repository.PaymentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger { }

@Service
class PaymentService(
    private val objectMapper: ObjectMapper,
    private val idempotencyHandler: IdempotencyHandler,
    private val paymentRepository: PaymentRepository,
    private val paymentRefundRepository: PaymentRefundRepository,
    private val outboxEventRepository: OutboxEventRepository
) {
    @Transactional
    fun handleOrderCreated(message: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(message.eventId) {
            logger.info { "주문 생성 이벤트 수신: eventId=${message.eventId}" }
            val payload = objectMapper.readValue(message.payload, OrderReservedPayload::class.java)

            val existing = paymentRepository.findByOrderId(payload.orderId)
            if (existing != null) {
                logger.info { "이미 결제 존재: ${existing.id}" }
                return@executeIdempotent
            }

            val payment = Payment(
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

            logger.info { "결제 생성: orderId=${payload.orderId}, amount=${payload.totalPaymentAmount}" }
            paymentRepository.save(payment)
        }
    }

    @Transactional
    fun handleOrderCanceled(message: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(message.eventId) {
            val payload = objectMapper.readValue(message.payload, OrderCanceledPayload::class.java)
            val payment = paymentRepository.findByOrderId(payload.orderId) ?: run {
                logger.warn { "취소 주문의 결제 정보 없음: orderId=${payload.orderId}" }
                return@executeIdempotent
            }

            if (payment.status != PaymentStatus.PAYMENT_COMPLETED &&
                payment.status != PaymentStatus.PAYMENT_PARTIALLY_REFUNDED
            ) {
                logger.info { "환불 불필요 결제 상태: orderId=${payload.orderId}, status=${payment.status}" }
                return@executeIdempotent
            }

            val refundEvents = payload.canceledSellerOrders.mapNotNull { canceled ->
                val existing = paymentRefundRepository.findByPaymentIdAndSellerOrderId(payment.id, canceled.sellerOrderId)
                if (existing != null) return@mapNotNull null

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
                OutboxEvent(
                    aggregateType = "PAYMENT_REFUND",
                    aggregateId = refund.id,
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

            payment.status = if (payment.refundedAmount >= payment.amount) {
                PaymentStatus.PAYMENT_REFUNDED
            } else {
                PaymentStatus.PAYMENT_PARTIALLY_REFUNDED
            }
            paymentRepository.save(payment)
            if (refundEvents.isNotEmpty()) outboxEventRepository.saveAll(refundEvents)

            logger.info { "환불 처리 완료: orderId=${payload.orderId}, refunds=${refundEvents.size}" }
        }
    }
}
