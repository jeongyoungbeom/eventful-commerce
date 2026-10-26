package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.shipping.domain.ShippingStatus
import com.eventfulcommerce.shipping.repository.ShippingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.*
import kotlin.random.Random

@Service
class ShippingService(
    private val idempotencyHandler: IdempotencyHandler,
    private val shippingRepository: ShippingRepository,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper,
    private val shippingCompletionWorker: ShippingCompletionWorker
) {

    @Transactional(rollbackFor = [Exception::class])
    fun handleOrderConfirmed(eventId: UUID, payloadJson: String) {
        idempotencyHandler.executeIdempotent(eventId) {
            val payload = objectMapper.readValue(payloadJson, OrderConfirmedPayload::class.java)
            payload.sellerOrders.sortedBy { it.sellerOrderId }.forEach { seller ->
                val inserted = shippingRepository.insertIfAbsent(UUID.randomUUID(), payload.orderId,
                    seller.sellerOrderId, payload.userId, ShippingStatus.PREPARING.name, Instant.now())
                val shipping = requireNotNull(shippingRepository.findBySellerOrderIdForUpdate(seller.sellerOrderId))
                check(shipping.orderId == payload.orderId && shipping.userId == payload.userId)
                if (inserted == 1) shipping.trackingNumber = generateTrackingNumber()
                // CANCELED 행은 확정보다 먼저 도착한 취소의 영속 기록이기도 하다.
            }
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    fun handleCancellationRequested(eventId: UUID, payloadJson: String) {
        idempotencyHandler.executeIdempotent(eventId) {
            val payload = objectMapper.readValue(payloadJson, ShippingCancellationRequestedPayload::class.java)
            shippingRepository.insertIfAbsent(UUID.randomUUID(), payload.orderId, payload.sellerOrderId,
                payload.userId, ShippingStatus.CANCELED.name, Instant.now())
            val shipping = requireNotNull(shippingRepository.findBySellerOrderIdForUpdate(payload.sellerOrderId))
            check(shipping.orderId == payload.orderId && shipping.userId == payload.userId)
            val priorRequest = shipping.cancellationRequestId
            check(priorRequest == null || priorRequest == payload.requestId) { "Conflicting cancellation request" }
            if (priorRequest == null) {
                val approved = shipping.shippedAt == null && shipping.status in setOf(
                    ShippingStatus.PREPARING, ShippingStatus.CANCELED, ShippingStatus.FAILED)
                shipping.cancellationRequestId = payload.requestId
                shipping.cancellationApproved = approved
                if (approved) shipping.status = ShippingStatus.CANCELED
            }
            outboxEventService.record(listOf(OutboxEvent(
                aggregateType = "SHIPPING", aggregateId = payload.orderId,
                eventType = "SHIPPING_CANCELLATION_DECIDED",
                payload = objectMapper.writeValueAsString(ShippingCancellationDecidedPayload(
                    payload.requestId, payload.orderId, payload.sellerOrderId, payload.userId,
                    requireNotNull(shipping.cancellationApproved))),
                status = OutboxStatus.PENDING
            )))
        }
    }

    @Transactional(rollbackFor = [Exception::class])
    fun startShipping(shippingId: UUID) {
        val shipping = shippingRepository.findByIdForUpdate(shippingId) ?: return
        if (shipping.status != ShippingStatus.PREPARING) return
        shipping.status = ShippingStatus.STARTED
        shipping.shippedAt = Instant.now()
        shippingRepository.save(shipping)
        outboxEventService.record(listOf(OutboxEvent(
            aggregateType = ShippingStatus.STARTED.name, aggregateId = shipping.id,
            eventType = "SHIPPING_STARTED",
            payload = objectMapper.writeValueAsString(ShippingStartedPayload(
                shipping.orderId, shipping.userId, shipping.trackingNumber ?: "")),
            status = OutboxStatus.PENDING
        )))
        scheduleCompletionAfterCommit(shipping.id)
    }

    private fun scheduleCompletionAfterCommit(shippingId: UUID) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            shippingCompletionWorker.completeAfterDelay(shippingId)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                shippingCompletionWorker.completeAfterDelay(shippingId)
            }
        })
    }

    /**
     * 운송장 번호 생성 (랜덤)
     */
    private fun generateTrackingNumber(): String {
        val prefix = "TRK"
        val random = Random.nextLong(100000000, 999999999)
        return "$prefix$random"
    }
}
