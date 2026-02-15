package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OrderReservedPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.ProcessedEvent
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.common.OrderConfirmedPayload
import com.eventfulcommerce.common.PaymentFailedPayload
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import com.eventfulcommerce.order.exception.InsufficientInventoryException
import kotlin.collections.map

private val logger = KotlinLogging.logger {}

@Service
class OrdersService(
    private val ordersRepository: OrdersRepository,
    private val outboxEventService: OutboxEventService,
    private val inventoryReservationService: InventoryReservationService,
    private val idempotencyHandler: IdempotencyHandler,
    private val objectMapper: ObjectMapper
) {
    private val ttlSeconds = 10 * 60L

    @Transactional
    fun orders(ordersRequests: List<OrdersRequest>): List<String> {
        val orderList = ordersRequests.map { it.toEntity() }
        val savedOrders = ordersRepository.saveAll(orderList)
        
        // 각 주문에 대해 재고 예약 시도
        val (successOrders, failedOrders) = savedOrders.partition { order ->
            val reservationId = inventoryReservationService.reserve(order.id, ttlSeconds)
            
            if (reservationId != null) {
                order.status = OrdersStatus.ORDER_RESERVED
                order.reservationId = reservationId
                order.expiresAt = Instant.now().plusSeconds(ttlSeconds)
                true
            } else {
                order.status = OrdersStatus.ORDER_CANCELED
                logger.warn { "재고 부족으로 주문 취소: orderId=${order.id}" }
                false
            }
        }
        
        // 모든 주문 상태 저장 (성공/실패 모두)
        ordersRepository.saveAll(savedOrders)
        logger.info { "주문 처리 완료 - 성공: ${successOrders.size}, 실패: ${failedOrders.size}" }
        
        // 성공한 주문만 이벤트 발행
        if (successOrders.isNotEmpty()) {
            val events = successOrders.map { order ->
                val payloadJson = objectMapper.writeValueAsString(
                    OrderReservedPayload(
                        orderId = order.id,
                        userId = order.userId,
                        totalAmount = order.totalAmount,
                        reservationId = order.reservationId!!,
                        expiresAt = order.expiresAt,
                        createdAt = order.createdAt,
                    )
                )

                OutboxEvent(
                    aggregateType = OrdersStatus.ORDER.toString(),
                    aggregateId = order.id,
                    eventType = OrdersStatus.ORDER_RESERVED.toString(),
                    payload = payloadJson,
                    status = OutboxStatus.PENDING
                )
            }

            logger.info { "주문 예약 완료 -> 결제 서비스로 전송: ${successOrders.size}건" }
            inventoryReservationService.getStockSummary()
            outboxEventService.recode(events)
        }
        
        // 실패한 주문이 있으면 예외 발생 (실패 정보 포함)
        if (failedOrders.isNotEmpty()) {
            val failedOrderIds = failedOrders.joinToString { it.id.toString() }
            throw InsufficientInventoryException("재고 부족 주문: $failedOrderIds")
        }

        return successOrders.map { it.id.toString() }
    }

    @Transactional
    fun handlePaymentCompleted(value: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(value.eventId) {
            val payload = objectMapper.readValue(value.payload, PaymentCompletedPayload::class.java)
            val order = ordersRepository.findById(payload.orderId).orElseThrow {
                IllegalStateException("주문을 찾을 수 없습니다: orderId=${payload.orderId}")
            }

            // 이미 확인된 주문인 경우 (멱등성)
            if (order.status == OrdersStatus.ORDER_CONFIRMED) {
                logger.info { "이미 확인된 주문입니다: orderId=${order.id}" }
                // 이 람다 블록만 종료.
                return@executeIdempotent
            }

            // 잘못된 상태인 경우
            if (order.status != OrdersStatus.ORDER_RESERVED) {
                logger.error { "잘못된 주문 상태: orderId=${order.id}, status=${order.status}, expected=ORDER_RESERVED" }
                // 이 람다 블록만 종료.
                return@executeIdempotent
            }

            val reservationId = order.reservationId
            if (reservationId == null) {
                logger.error { "예약 ID가 없습니다: orderId=${order.id}" }
                // 이 람다 블록만 종료.
                return@executeIdempotent
            }

            logger.info { "결제 완료 - 재고 확정: orderId=${order.id}, reservationId=$reservationId" }
            inventoryReservationService.commit(reservationId)

            order.status = OrdersStatus.ORDER_CONFIRMED
            ordersRepository.save(order)

            val confirmedPayload = OrderConfirmedPayload(
                orderId = order.id,
                userId = order.userId,
                totalAmount = order.totalAmount,
                confirmedAt = Instant.now()
            )

            val outboxEvent = OutboxEvent(
                aggregateType = OrdersStatus.ORDER.toString(),
                aggregateId = order.id,
                eventType = OrdersStatus.ORDER_CONFIRMED.toString(),
                payload = objectMapper.writeValueAsString(confirmedPayload),
                status = OutboxStatus.PENDING
            )

            logger.info { "주문 확정 완료 -> 배송 서비스로 전송: orderId=${order.id}" }
            outboxEventService.recode(listOf(outboxEvent))
        }
    }

    @Transactional
    fun handlePaymentFailed(value: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(value.eventId) {
            val payload = objectMapper.readValue(value.payload, PaymentFailedPayload::class.java)
            val order = ordersRepository.findById(payload.orderId).orElseThrow {
                IllegalStateException("주문을 찾을 수 없습니다: orderId=${payload.orderId}")
            }

            if (order.status != OrdersStatus.ORDER_RESERVED) {
                logger.warn { "예약 상태가 아닌 주문의 결제 실패 처리: orderId=${order.id}, status=${order.status}" }
                return@executeIdempotent
            }

            val reservationId = order.reservationId
            if (reservationId != null) {
                logger.info { "결제 실패 - 재고 해제: orderId=${order.id}, reservationId=$reservationId" }
                inventoryReservationService.release(reservationId)
            } else {
                logger.warn { "예약 ID가 없습니다: orderId=${order.id}" }
            }

            order.status = OrdersStatus.ORDER_CANCELED
            ordersRepository.save(order)
            logger.info { "주문 취소 완료: orderId=${order.id}" }
        }
    }
}