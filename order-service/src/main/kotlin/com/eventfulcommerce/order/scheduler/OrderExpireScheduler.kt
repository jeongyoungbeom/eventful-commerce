package com.eventfulcommerce.order.scheduler

import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.eventfulcommerce.order.service.OrderExpirationService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.OptimisticLockException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Component
class OrderExpireScheduler(
    private val ordersRepository: OrdersRepository,
    private val orderExpirationService: OrderExpirationService
) {

    @Scheduled(fixedDelayString = "10000")
    fun expireReservedOrders() {
        val now = Instant.now()
        val expiredOrders = ordersRepository.findByStatusAndExpiresAtBefore(
            OrdersStatus.ORDER_RESERVED, 
            now
        )

        if (expiredOrders.isEmpty()) {
            logger.debug { "만료된 주문 없음" }
            return
        }

        logger.info { "만료된 주문 처리 시작: ${expiredOrders.size}건" }
        
        var successCount = 0
        var conflictCount = 0
        var failCount = 0
        
        expiredOrders.forEach { order ->
            try {
                orderExpirationService.expireOrder(order.id)
                successCount++
            } catch (e: OptimisticLockException) {
                logger.info { "주문이 이미 다른 프로세스에 의해 처리됨 (낙관적 락): orderId=${order.id}" }
                conflictCount++
            } catch (e: ObjectOptimisticLockingFailureException) {
                logger.info { "주문이 이미 다른 프로세스에 의해 처리됨 (낙관적 락): orderId=${order.id}" }
                conflictCount++
            } catch (e: Exception) {
                logger.error(e) { "주문 만료 처리 실패: orderId=${order.id}" }
                failCount++
            }
        }
        
        logger.info { "만료 주문 처리 완료 - 성공: $successCount, 충돌: $conflictCount, 실패: $failCount" }
    }
}