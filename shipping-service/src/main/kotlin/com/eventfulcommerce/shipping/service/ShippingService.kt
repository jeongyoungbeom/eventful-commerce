package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.shipping.domain.Shipping
import com.eventfulcommerce.shipping.domain.ShippingStatus
import com.eventfulcommerce.shipping.repository.ShippingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.*
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

@Service
class ShippingService(
    private val idempotencyHandler: IdempotencyHandler,
    private val shippingRepository: ShippingRepository,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper,
    private val shippingCompletionWorker: ShippingCompletionWorker
) {

    /**
     * ORDER_CONFIRMED 이벤트 처리
     */
    @Transactional
    fun handleOrderConfirmed(eventId: UUID, payloadJson: String) {
        idempotencyHandler.executeIdempotent(eventId) {
            val payload = objectMapper.readValue(payloadJson, OrderConfirmedPayload::class.java)

            payload.sellerOrders.forEach { sellerOrder ->
                if (shippingRepository.existsBySellerOrderId(sellerOrder.sellerOrderId)) {
                    logger.warn { "이미 배송이 생성됨: sellerOrderId=${sellerOrder.sellerOrderId}" }
                    return@forEach
                }

                val trackingNumber = generateTrackingNumber()
                val shipping = Shipping(
                    orderId = payload.orderId,
                    sellerOrderId = sellerOrder.sellerOrderId,
                    userId = payload.userId,
                    status = ShippingStatus.PREPARING,
                    trackingNumber = trackingNumber
                )
                shippingRepository.save(shipping)

                logger.info { "배송 생성: orderId=${payload.orderId}, sellerOrderId=${sellerOrder.sellerOrderId}, trackingNumber=$trackingNumber" }
                startShipping(shipping.id)
                scheduleCompletionAfterCommit(shipping.id)
            }
        }
    }

    /**
     * 배송 시작
     */
    @Transactional
    fun startShipping(shippingId: UUID) {
        val shipping = shippingRepository.findById(shippingId).orElse(null)
        if (shipping == null) {
            logger.warn { "배송을 찾을 수 없음: shippingId=$shippingId" }
            return
        }

        // 상태 변경
        shipping.status = ShippingStatus.STARTED
        shipping.shippedAt = Instant.now()
        shippingRepository.save(shipping)

        // SHIPPING_STARTED 이벤트 발행
        val payload = ShippingStartedPayload(
            orderId = shipping.orderId,
            userId = shipping.userId,
            trackingNumber = shipping.trackingNumber ?: ""
        )

        val outboxEvent = OutboxEvent(
            aggregateType = ShippingStatus.STARTED.toString(),
            aggregateId = shipping.id,
            eventType = "SHIPPING_STARTED",
            payload = objectMapper.writeValueAsString(payload),
            status = OutboxStatus.PENDING
        )
        outboxEventService.record(listOf(outboxEvent))

        logger.info { "🚚 배송 시작: orderId=${shipping.orderId}, trackingNumber=${shipping.trackingNumber}" }
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
