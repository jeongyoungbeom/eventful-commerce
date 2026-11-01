package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.shipping.repository.DirectShipmentRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class DirectShippingScheduler(private val shipments: DirectShipmentRepository, private val service: DirectShippingService,
    @Value("\${shipping.direct-completion-delay-ms:10000}") private val delay: Long) {
    init { require(delay >= 0) }
    @Scheduled(fixedDelayString = "\${shipping.recovery-delay-ms:1000}")
    fun complete() {
        shipments.due(Instant.now().minusMillis(delay), PageRequest.of(0, 100)).forEach { id ->
            runCatching { service.complete(id) }.onFailure { logger.error(it) { "직매입 배송 완료 재시도 대기: $id" } }
        }
    }
    private companion object { val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {} }
}
