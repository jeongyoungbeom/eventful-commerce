package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.OrderCanceledPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * 주문 취소 실행자 (트랜잭션 전담)
 * 
 * OrderCancelService와 분리하여 
 * Spring AOP 프록시가 제대로 작동하도록 함
 */
@Service
class OrderCancelExecutor(
    private val ordersRepository: OrdersRepository,
    private val inventoryReservationService: InventoryReservationService,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper
) {

    /**
     * 실제 취소 로직 (트랜잭션 적용)
     */
    @Transactional
    fun execute(orderId: UUID, reason: String): Boolean {
        // 1. 주문 조회
        val order = ordersRepository.findById(orderId).orElse(null)
        if (order == null) {
            logger.warn { "주문을 찾을 수 없음: orderId=$orderId" }
            return false
        }
        
        // 2. 상태 확인 - 예약 상태만 취소 가능
        if (order.status != OrdersStatus.ORDER_RESERVED) {
            logger.info { "취소 불가능한 주문 상태: orderId=$orderId, status=${order.status}" }
            return false
        }
        
        // 3. 재고 해제
        val reservationId = order.reservationId
        if (reservationId != null) {
            logger.info { "재고 해제: orderId=$orderId, reservationId=$reservationId" }
            inventoryReservationService.release(order.productId, reservationId)
        } else {
            logger.warn { "예약 ID가 없습니다: orderId=$orderId" }
        }
        
        // 4. 주문 상태 변경
        order.status = OrdersStatus.ORDER_CANCELED
        ordersRepository.save(order)

        // 5. ORDER_CANCELED 이벤트 발행
        val payload = OrderCanceledPayload(
            orderId = orderId,
            userId = order.userId,
            reason = reason
        )

        val outboxEvent = OutboxEvent(
            aggregateType = OrdersStatus.ORDER_CANCELED.toString(),
            aggregateId = orderId,
            eventType = "ORDER_CANCELED",
            payload = objectMapper.writeValueAsString(payload),
            status = OutboxStatus.PENDING
        )

        outboxEventService.record(listOf(outboxEvent))
        
        logger.info { "✅ 주문 취소 완료: orderId=$orderId, reason=$reason" }
        return true
    }
}
