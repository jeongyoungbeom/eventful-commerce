package com.eventfulcommerce.payment.service

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.PaymentFailedPayload
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.payment.domain.PaymentStatus
import com.eventfulcommerce.payment.domain.PaymentWebhookRequest
import com.eventfulcommerce.payment.domain.entity.Payment
import com.eventfulcommerce.payment.repository.PaymentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val logger = KotlinLogging.logger { }

@Service
class PaymentWebhookService(
    private val paymentRepository: PaymentRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun handle(request: PaymentWebhookRequest) {
        val payment = (paymentRepository.findByOrderId(request.orderId)
            ?: throw IllegalArgumentException("결제 정보를 찾을 수 없습니다: orderId= ${request.orderId}"))

        if (payment.status == PaymentStatus.PAYMENT_COMPLETED || payment.status == PaymentStatus.PAYMENT_FAILED) return

        if (request.result == "SUCCESS") {
            successPayment(payment)
        } else {
            failedPayment(payment, request)
        }

    }

    private fun successPayment(payment: Payment) {
        val paymentPayload = PaymentCompletedPayload(
            paymentId = payment.id,
            orderId = payment.orderId,
            amount = payment.amount,
            reservationId = payment.reservationId,
            completedAt = Instant.now()
        )
        val outboxEventPayload = objectMapper.writeValueAsString(paymentPayload)

        logger.info { "결제 완료" }
        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = PaymentStatus.PAYMENT.toString(),
                aggregateId = payment.id,
                eventType = PaymentStatus.PAYMENT_COMPLETED.toString(),
                payload = outboxEventPayload,
                status = OutboxStatus.PENDING,
            )
        )
        payment.status = PaymentStatus.PAYMENT_COMPLETED
    }

    private fun failedPayment(
        payment: Payment,
        request: PaymentWebhookRequest
    ) {
        payment.status = PaymentStatus.PAYMENT_FAILED

        val paymentPayload = PaymentFailedPayload(
            paymentId = payment.id,
            orderId = payment.orderId,
            amount = payment.amount,
            reservationId = payment.reservationId!!,
            failedAt = Instant.now(),
            pgTxId = request.pgTxId!!
        )

        logger.info { "결제 실패" }
        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = PaymentStatus.PAYMENT.toString(),
                aggregateId = payment.id,
                eventType = PaymentStatus.PAYMENT_FAILED.toString(),
                payload = objectMapper.writeValueAsString(paymentPayload),
                status = OutboxStatus.PENDING
            )
        )
    }
}