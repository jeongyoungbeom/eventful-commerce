package com.eventfulcommerce.payment.service

import com.eventfulcommerce.common.OrderCreatedPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.ProcessedEvent
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.payment.domain.PaymentStatus
import com.eventfulcommerce.payment.domain.entity.Payment
import com.eventfulcommerce.payment.repository.PaymentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
        val payload = objectMapper.readValue(readValue.payload, OrderCreatedPayload::class.java)

        val payment = Payment(
            orderId = payload.orderId,
            status = PaymentStatus.ACCEPTED,
            amount = payload.totalAmount
        )

        paymentRepository.save(payment)

        val paymentPayload = mapOf(
            "paymentId" to payment.id,
            "orderId" to payment.orderId,
            "amount" to payment.amount,
            "status" to payment.status,
        )
        val outboxEventPayload = objectMapper.writeValueAsString(paymentPayload)

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = "PAYMENT",
                aggregateId = payment.id,
                eventType = "PAYMENT_COMPLETED",
                payload = outboxEventPayload,
                status = OutboxStatus.PENDING,
            )
        )
    }
}