package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.ProcessedEvent
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.shipping.domain.Shipping
import com.eventfulcommerce.shipping.repository.ShippingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class ShippingService(
    private val processedEventRepository: ProcessedEventRepository,
    private val shippingRepository: ShippingRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun handlePaymentCompleted(eventId: UUID, payloadJson: String) {
        try {
            processedEventRepository.save(ProcessedEvent(eventId))
        } catch (e: Exception) {
            return
        }

        val payload = objectMapper.readValue(payloadJson, PaymentCompletedPayload::class.java)

        // 중복 저장 체크
        if (shippingRepository.existsByOrderId(payload.orderId)) return
        val shipping = shippingRepository.save(Shipping(orderId = payload.orderId))

        val shippingCreatedPayload = mapOf(
            "shippingId" to shipping.id,
            "orderId" to shipping.orderId,
            "status" to shipping.status.name
        )

        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = "SHIPPING",
                aggregateId = shipping.id,
                eventType = "SHIPPING_CREATED",
                payload = objectMapper.writeValueAsString(shippingCreatedPayload),
                status = OutboxStatus.PENDING
            )
        )
    }
}