package com.eventfulcommerce.order.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class OrderExpirationService(
    private val orderCancelService: OrderCancelService
) {

    fun expireOrder(orderId: UUID) {
        logger.info { "주문 만료 처리 시작: orderId=$orderId" }
        
        // 분산락을 사용한 취소 로직 호출
        val success = orderCancelService.cancel(orderId, "주문 만료")
        
        if (success) {
            logger.info { "주문 만료 처리 완료: orderId=$orderId" }
        } else {
            logger.warn { "주문 만료 처리 실패: orderId=$orderId (이미 처리되었거나 락 획득 실패)" }
        }
    }
}
