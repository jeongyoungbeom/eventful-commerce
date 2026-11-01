package com.eventfulcommerce.order.scheduler

import com.eventfulcommerce.order.repository.MixedOrderAttemptRepository
import com.eventfulcommerce.order.service.MixedOrdersService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@ConditionalOnProperty(name = ["order.direct-inventory.recovery-enabled"], havingValue = "true", matchIfMissing = true)
class MixedOrderRecoveryScheduler(private val attempts: MixedOrderAttemptRepository, private val service: MixedOrdersService) {
    private val logger = KotlinLogging.logger {}

    @Scheduled(fixedDelayString = "\${order.direct-inventory.recovery-delay-ms:5000}", scheduler = "mixedOrderTaskScheduler")
    fun recover() {
        attempts.findRecoverable(Instant.now(), PageRequest.of(0, 20)).forEach { id ->
            runCatching { service.recover(id) }.onFailure {
                logger.warn { "혼합 주문 복구 보류: orderId=$id" }
            }
        }
    }
}
