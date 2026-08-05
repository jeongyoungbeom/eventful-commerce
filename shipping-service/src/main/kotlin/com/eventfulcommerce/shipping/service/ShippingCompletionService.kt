package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.ShippingCompletedPayload
import com.eventfulcommerce.shipping.domain.ShippingStatus
import com.eventfulcommerce.shipping.repository.ShippingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

private val completionLogger = KotlinLogging.logger { }

@Service
class ShippingCompletionService(
    private val shippingRepository: ShippingRepository,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun complete(shippingId: UUID) {
        val shipping = shippingRepository.findByIdForUpdate(shippingId) ?: run {
            completionLogger.warn { "배송을 찾을 수 없음: shippingId=$shippingId" }
            return
        }
        if (shipping.status == ShippingStatus.COMPLETED || shipping.status == ShippingStatus.FAILED) return
        check(shipping.status == ShippingStatus.STARTED) {
            "Shipping is not ready to complete: shippingId=$shippingId, status=${shipping.status}"
        }

        shipping.status = ShippingStatus.COMPLETED
        shipping.completedAt = Instant.now()
        shippingRepository.save(shipping)
        outboxEventService.record(
            listOf(
                OutboxEvent(
                    aggregateType = ShippingStatus.COMPLETED.toString(),
                    aggregateId = shipping.id,
                    eventType = "SHIPPING_COMPLETED",
                    payload = objectMapper.writeValueAsString(
                        ShippingCompletedPayload(orderId = shipping.orderId, userId = shipping.userId)
                    ),
                    status = OutboxStatus.PENDING
                )
            )
        )
        completionLogger.info { "배송 완료: orderId=${shipping.orderId}, shippingId=${shipping.id}" }
    }
}
