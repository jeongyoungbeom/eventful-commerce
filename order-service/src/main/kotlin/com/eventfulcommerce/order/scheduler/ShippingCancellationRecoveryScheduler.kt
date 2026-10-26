package com.eventfulcommerce.order.scheduler

import com.eventfulcommerce.order.repository.SellerOrderRepository
import com.eventfulcommerce.order.service.OrderCancelExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ShippingCancellationRecoveryScheduler(
    private val sellerOrders: SellerOrderRepository,
    private val cancellation: OrderCancelExecutor
) {
    @Scheduled(fixedDelayString = "\${order.cancellation-recovery-delay-ms:60000}")
    fun recover() {
        val cutoff = Instant.now().minusSeconds(60)
        sellerOrders.findPendingCancellationOrderIds(cutoff, PageRequest.of(0, 100)).distinct().forEach {
            runCatching { cancellation.recoverPending(it, cutoff) }
                .onFailure { error -> logger.error(error) { "배송 취소 요청 복구 실패: orderId=$it" } }
        }
    }

    private companion object { val logger = KotlinLogging.logger {} }
}
