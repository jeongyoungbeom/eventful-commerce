package com.eventfulcommerce.order.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class OrderExpirationService(
    private val orderCancelExecutor: OrderCancelExecutor
) {
    fun expireOrder(orderId: UUID): Boolean {
        val expired = orderCancelExecutor.executeExpired(orderId)
        if (expired) logger.info { "주문 만료 처리 완료: orderId=$orderId" }
        return expired
    }
}
