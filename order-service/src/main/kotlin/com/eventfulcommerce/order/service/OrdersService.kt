package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OrderReservedPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.OrderConfirmedPayload
import com.eventfulcommerce.common.PaymentFailedPayload
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
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
        // 1단계: 주문 엔티티 생성 및 초기 저장
        val orderList = ordersRequests.map { it.toEntity() }
        val savedOrders = ordersRepository.saveAll(orderList)

        logger.info { "주문 생성 시작 - 총 ${savedOrders.size}건" }

        // 2단계: 모든 주문에 대해 재고 예약 시도
        val reservationResults = mutableListOf<Pair<Orders, UUID?>>()

        for (order in savedOrders) {
            val reservationId = inventoryReservationService.reserve(order.id, ttlSeconds)
            reservationResults.add(order to reservationId)

            // ⚠️ 하나라도 실패하면 즉시 전체 롤백!
            if (reservationId == null) {
                logger.warn { "재고 부족 발견 - 전체 주문 롤백: orderId=${order.id}" }

                // 이미 예약한 재고들 모두 해제
                reservationResults.forEach { (prevOrder, prevReservationId) ->
                    if (prevReservationId != null) {
                        inventoryReservationService.release(prevReservationId)
                        logger.info { "재고 예약 롤백: orderId=${prevOrder.id}, reservationId=$prevReservationId" }
                    }
                }

                // 전체 실패 예외 발생 (트랜잭션 롤백됨)
                throw InsufficientInventoryException(
                    message = "재고 부족으로 전체 주문이 취소되었습니다. 실패 주문: ${order.id}",
                    failedOrderIds = savedOrders.joinToString { it.id.toString() }
                )
            }
        }

        // 3단계: 모든 재고 예약 성공 → 주문 상태 업데이트
        savedOrders.forEachIndexed { index, order ->
            val reservationId = reservationResults[index].second!!
            order.status = OrdersStatus.ORDER_RESERVED
            order.reservationId = reservationId
            order.expiresAt = Instant.now().plusSeconds(ttlSeconds)
        }

        ordersRepository.saveAll(savedOrders)
        logger.info { "모든 주문 예약 성공 - ${savedOrders.size}건" }

        // 4단계: 이벤트 발행 (모두 성공한 경우에만)
        val events = savedOrders.map { order ->
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

        outboxEventService.record(events)
        inventoryReservationService.getStockSummary()

        logger.info { "주문 처리 완료 - 결제 서비스로 전송: ${savedOrders.size}건" }

        return savedOrders.map { it.id.toString() }
    }

    @Transactional
    fun handlePaymentCompleted(value: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(value.eventId) {
            val payload = objectMapper.readValue(value.payload, PaymentCompletedPayload::class.java)
            val order = ordersRepository.findById(payload.orderId).orElseThrow {
                IllegalStateException("주문을 찾을 수 없습니다: orderId=${payload.orderId}")
            }

            if (order.status == OrdersStatus.ORDER_CONFIRMED) {
                logger.info { "이미 확인된 주문입니다: orderId=${order.id}" }
                return@executeIdempotent
            }

            if (order.status != OrdersStatus.ORDER_RESERVED) {
                logger.error { "잘못된 주문 상태: orderId=${order.id}, status=${order.status}, expected=ORDER_RESERVED" }
                return@executeIdempotent
            }

            val reservationId = order.reservationId
            if (reservationId == null) {
                logger.error { "예약 ID가 없습니다: orderId=${order.id}" }
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
            outboxEventService.record(listOf(outboxEvent))
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