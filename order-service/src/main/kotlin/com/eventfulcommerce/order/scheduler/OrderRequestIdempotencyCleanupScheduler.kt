package com.eventfulcommerce.order.scheduler

import com.eventfulcommerce.order.repository.OrderRequestIdempotencyRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Component
class OrderRequestIdempotencyCleanupScheduler(
    private val orderRequestIdempotencyRepository: OrderRequestIdempotencyRepository
) {
    @Scheduled(fixedDelayString = "\${order.idempotency.cleanup-fixed-delay-ms:3600000}")
    @Transactional
    fun cleanExpiredRecords() {
        val deleted = orderRequestIdempotencyRepository.deleteAllExpired(Instant.now())
        if (deleted > 0) {
            logger.info { "Expired order idempotency records deleted: count=$deleted" }
        }
    }
}
