package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.common.OrderConfirmedPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.ProcessedEvent
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.shipping.domain.Shipping
import com.eventfulcommerce.shipping.domain.ShippingStatus
import com.eventfulcommerce.shipping.repository.ShippingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

private val logger = KotlinLogging.logger { }

@Service
class ShippingService(
    private val processedEventRepository: ProcessedEventRepository,
    private val shippingRepository: ShippingRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun handleOrderConfirmed(eventId: UUID, payloadJson: String) {
        try {
            processedEventRepository.save(ProcessedEvent(eventId))
        } catch (e: Exception) {
            return
        }

        val payload = objectMapper.readValue(payloadJson, OrderConfirmedPayload::class.java)

        // 중복 저장 체크
        if (shippingRepository.existsByOrderId(payload.orderId)) return
        val shipping = shippingRepository.save(Shipping(orderId = payload.orderId))

        val paymentCompletePayload = mapOf(
            "shippingId" to shipping.id,
            "orderId" to shipping.orderId,
            "status" to shipping.status.name
        )
        logger.info { "배송 준비" }
        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = ShippingStatus.SHIPPING.toString(),
                aggregateId = shipping.id,
                eventType = ShippingStatus.SHIPPING_READY.toString(),
                payload = objectMapper.writeValueAsString(paymentCompletePayload),
                status = OutboxStatus.PENDING
            )
        )
    }
}