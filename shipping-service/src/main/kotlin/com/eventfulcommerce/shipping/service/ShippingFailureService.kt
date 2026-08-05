package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.ShippingFailedPayload
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.shipping.domain.ShippingStatus
import com.eventfulcommerce.shipping.repository.ShippingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class ShippingFailureService(
    private val shippingRepository: ShippingRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun fail(shippingId: UUID, exception: Exception) {
        val shipping = shippingRepository.findById(shippingId).orElse(null) ?: return
        if (shipping.status == ShippingStatus.COMPLETED || shipping.status == ShippingStatus.FAILED) return

        shipping.status = ShippingStatus.FAILED
        val payload = ShippingFailedPayload(
            orderId = shipping.orderId,
            sellerOrderId = shipping.sellerOrderId,
            userId = shipping.userId,
            reason = (exception.message ?: exception.javaClass.name).take(500),
            failedAt = Instant.now()
        )
        outboxEventRepository.save(
            OutboxEvent(
                aggregateType = "SHIPPING",
                aggregateId = shipping.id,
                eventType = "SHIPPING_FAILED",
                payload = objectMapper.writeValueAsString(payload),
                status = OutboxStatus.PENDING
            )
        )
    }
}
