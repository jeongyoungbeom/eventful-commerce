package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.shipping.domain.ShippingStatus
import com.eventfulcommerce.shipping.repository.ShippingRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class ShippingDispatchScheduler(
    private val repository: ShippingRepository,
    private val service: ShippingService,
    @param:Value("\${shipping.preparation-delay-ms:30000}") private val preparationDelayMs: Long
) {
    init { require(preparationDelayMs >= 0) }

    @Scheduled(fixedDelayString = "\${shipping.dispatch-delay-ms:1000}")
    fun dispatch() {
        repository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(
            ShippingStatus.PREPARING, Instant.now().minusMillis(preparationDelayMs), PageRequest.of(0, 100)
        ).forEach { shipping ->
            runCatching { service.startShipping(shipping.id) }
                .onFailure { logger.error(it) { "배송 출고 재시도 대기: shippingId=${shipping.id}" } }
        }
    }

    private companion object { val logger = KotlinLogging.logger {} }
}
