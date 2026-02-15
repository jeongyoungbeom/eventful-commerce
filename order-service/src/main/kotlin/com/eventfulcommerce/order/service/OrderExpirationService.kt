package com.eventfulcommerce.order.service

import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class OrderExpirationService(
    private val ordersRepository: OrdersRepository,
    private val inventoryReservationService: InventoryReservationService
) {

    @Transactional
    fun expireOrder(orderId: UUID) {
        val order = ordersRepository.findById(orderId).orElse(null) ?: run {
            logger.warn { "주문을 찾을 수 없음: orderId=$orderId" }
            return
        }
        
        // 낙관적 락을 활용한 동시성 제어
        // 이미 다른 트랜잭션에서 상태가 변경되었다면 OptimisticLockException 발생
        if (order.status != OrdersStatus.ORDER_RESERVED) {
            logger.info { "이미 처리된 주문: orderId=$orderId, status=${order.status}" }
            return
        }
        
        val reservationId = order.reservationId
        if (reservationId != null) {
            logger.info { "만료된 주문 재고 해제: orderId=$orderId, reservationId=$reservationId" }
            inventoryReservationService.release(reservationId)
        } else {
            logger.warn { "예약 ID가 없는 만료 주문: orderId=$orderId" }
        }

        order.status = OrdersStatus.ORDER_EXPIRED
        ordersRepository.save(order) // @Version으로 낙관적 락 체크
        
        logger.info { "주문 만료 처리 완료: orderId=$orderId" }
    }
}
