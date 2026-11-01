package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.shipping.domain.ShippingStatus
import com.eventfulcommerce.shipping.repository.DirectShipmentRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@Transactional(rollbackFor = [Exception::class])
class DirectShippingService(private val shipments: DirectShipmentRepository, private val mapper: ObjectMapper,
    private val outbox: OutboxEventService) {
    fun shipped(payload: DirectFulfillmentPayload) {
        val content = mapper.writeValueAsString(payload)
        val inserted = shipments.insertIfAbsent(payload.reservationId, payload.orderId, payload.sellerOrderId,
            payload.warehouseId, content, Instant.now())
        val shipment = requireNotNull(shipments.lock(payload.reservationId))
        check(shipment.requestContent == content) { "출고 지시의 배송 원본 충돌" }
        if (inserted == 1) record(payload, "DIRECT_SHIPPING_STARTED")
    }

    fun complete(id: UUID) {
        val shipment = shipments.lock(id) ?: return
        if (shipment.status != ShippingStatus.STARTED) return
        shipment.status = ShippingStatus.COMPLETED
        shipment.completedAt = Instant.now()
        record(mapper.readValue(shipment.requestContent, DirectFulfillmentPayload::class.java), "DIRECT_SHIPPING_COMPLETED")
    }

    private fun record(payload: DirectFulfillmentPayload, type: String) {
        outbox.record(listOf(OutboxEvent(aggregateType = "DIRECT_SHIPMENT", aggregateId = payload.orderId,
            eventType = type, payload = mapper.writeValueAsString(payload))))
    }
}
