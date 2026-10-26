package com.eventfulcommerce.payment.service

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.PaymentCompletedSellerPayload
import com.eventfulcommerce.common.PaymentFailedPayload
import com.eventfulcommerce.common.metrics.EventfulBusinessMetrics
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
    private val objectMapper: ObjectMapper,
    private val paymentService: PaymentService,
    private val businessMetrics: EventfulBusinessMetrics
) {
    @Transactional(rollbackFor = [Exception::class])
    fun handle(request: PaymentWebhookRequest) {
        require(request.result == "SUCCESS" || request.result == "FAILED") { "허용되지 않은 결제 결과입니다" }
        val amount = requireNotNull(request.amount) { "결제 금액은 필수입니다" }
        require(amount > 0) { "결제 금액은 양수여야 합니다" }
        val pgTxId = requireNotNull(request.pgTxId) { "PG 거래 ID는 필수입니다" }
        require(pgTxId.isNotBlank() && pgTxId.none { it.isWhitespace() || it.isISOControl() } && pgTxId.length <= 200) { "잘못된 PG 거래 ID입니다" }

        val payment = (paymentRepository.findByOrderIdForUpdate(request.orderId)
            ?: throw IllegalArgumentException("결제 정보를 찾을 수 없습니다: orderId= ${request.orderId}"))

        require(amount == payment.amount) { "웹훅 금액이 결제 금액과 일치하지 않습니다" }
        require(payment.pgTxId == null || payment.pgTxId == pgTxId) { "기록된 PG 거래 ID와 일치하지 않습니다" }
        if (payment.status != PaymentStatus.PAYMENT_RESERVED) return

        val owner = paymentRepository.findByPgTxId(pgTxId)
        require(owner == null || owner.id == payment.id) { "다른 결제에 사용된 PG 거래 ID입니다" }
        // UNIQUE는 서로 다른 Payment 행에서 동시에 같은 ID를 채택하는 경합도 거부한다.
        payment.pgTxId = pgTxId

        if (request.result == "SUCCESS") {
            successPayment(payment)
            businessMetrics.increment("payment.webhook", "completed")
        } else {
            failedPayment(payment, request)
            businessMetrics.increment("payment.webhook", "failed")
        }

    }

    private fun successPayment(payment: Payment) {
        val paymentPayload = PaymentCompletedPayload(
            paymentId = payment.id,
            orderId = payment.orderId,
            userId = payment.userId,
            amount = payment.amount,
            sellerOrders = objectMapper.readValue(payment.sellerOrdersJson, Array<PaymentCompletedSellerPayload>::class.java).toList(),
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
        paymentService.refundPendingCancellation(payment.orderId)
    }

    private fun failedPayment(
        payment: Payment,
        request: PaymentWebhookRequest
    ) {
        payment.status = PaymentStatus.PAYMENT_FAILED
        payment.pendingCancellationPayload = null
        payment.cancellationRequested = false

        val paymentPayload = PaymentFailedPayload(
            paymentId = payment.id,
            orderId = payment.orderId,
            amount = payment.amount,
            failedAt = Instant.now(),
            pgTxId = request.pgTxId ?: ""
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
