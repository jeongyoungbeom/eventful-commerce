package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.shipping.domain.Shipping
import com.eventfulcommerce.shipping.domain.ShippingStatus
import com.eventfulcommerce.shipping.repository.ShippingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

@Service
class ShippingService(
    private val processedEventRepository: ProcessedEventRepository,
    private val shippingRepository: ShippingRepository,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper,
    @Value("\${shipping.completion-delay-ms}") private val completionDelayMs: Long
) {

    /**
     * ORDER_CONFIRMED 이벤트 처리
     */
    @Transactional
    fun handleOrderConfirmed(eventId: UUID, payloadJson: String) {
        // 멱등성 체크
        try {
            processedEventRepository.save(ProcessedEvent(eventId))
        } catch (e: DataIntegrityViolationException) {
            logger.info { "이미 처리된 이벤트: eventId=$eventId" }
            return
        }

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
            completeShippingAsync(shipping.id)
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

    /**
     * 배송 완료 (비동기, 10초 후)
     */
    @Async
    fun completeShippingAsync(shippingId: UUID) {
        try {
            Thread.sleep(completionDelayMs)
            completeShipping(shippingId)
        } catch (e: Exception) {
            logger.error(e) { "배송 완료 처리 실패: shippingId=$shippingId" }
        }
    }

    /**
     * 배송 완료
     */
    @Transactional
    fun completeShipping(shippingId: UUID) {
        val shipping = shippingRepository.findById(shippingId).orElse(null)
        if (shipping == null) {
            logger.warn { "배송을 찾을 수 없음: shippingId=$shippingId" }
            return
        }

        // 상태 변경
        shipping.status = ShippingStatus.COMPLETED
        shipping.completedAt = Instant.now()
        shippingRepository.save(shipping)

        // SHIPPING_COMPLETED 이벤트 발행
        val payload = ShippingCompletedPayload(
            orderId = shipping.orderId,
            userId = shipping.userId
        )

        val outboxEvent = OutboxEvent(
            aggregateType = ShippingStatus.COMPLETED.toString(),
            aggregateId = shipping.id,
            eventType = "SHIPPING_COMPLETED",
            payload = objectMapper.writeValueAsString(payload),
            status = OutboxStatus.PENDING
        )
        outboxEventService.record(listOf(outboxEvent))

        logger.info { "✅ 배송 완료: orderId=${shipping.orderId}" }
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
