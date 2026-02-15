package com.eventfulcommerce.payment.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OrderReservedPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.ProcessedEvent
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.payment.domain.PaymentStatus
import com.eventfulcommerce.payment.domain.entity.Payment
import com.eventfulcommerce.payment.repository.PaymentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val logger = KotlinLogging.logger { }

@Service
class PaymentService(
    private val objectMapper: ObjectMapper,
    private val idempotencyHandler: IdempotencyHandler,
    private val paymentRepository: PaymentRepository
) {
    @Transactional
    fun handleOrderCreated(message: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(message.eventId) {
            logger.info { "주문 생성 이벤트 수신: eventId=${message.eventId}" }
            val payload = objectMapper.readValue(message.payload, OrderReservedPayload::class.java)

            val payment = Payment(
                orderId = payload.orderId,
                status = PaymentStatus.PAYMENT_RESERVED,
                amount = payload.totalAmount,
                reservationId = payload.reservationId,
            )

            logger.info { "결제 생성: orderId=${payload.orderId}, amount=${payload.totalAmount}" }
            paymentRepository.save(payment)
        }
    }
}