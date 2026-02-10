package com.eventfulcommerce.payment.service

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
    private val processedEventRepository: ProcessedEventRepository,
    private val paymentRepository: PaymentRepository,
    private val outboxEventRepository: OutboxEventRepository
) {
    @Transactional
    fun handleOrderCreated(readValue: OutboxEventMessage) {
        val eventId = readValue.eventId
        try {
            processedEventRepository.save(ProcessedEvent(eventId))
        } catch (e: Exception) {
            // 로그 남길 것
            return
        }
        println("PAYLOAD_JSON = $readValue")
        val payload = objectMapper.readValue(readValue.payload, OrderReservedPayload::class.java)

        val payment = Payment(
            orderId = payload.orderId,
            status = PaymentStatus.PAYMENT_RESERVED,
            amount = payload.totalAmount
        )

        logger.info { "결제 진행" }
        paymentRepository.save(payment)
        Thread.sleep(3000L)
        val paymentPayload = PaymentCompletedPayload(
            paymentId = payment.id,
            orderId = payment.orderId,
            amount = payment.amount,
            reservationId = payload.reservationId,
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
}