package com.eventfulcommerce.inventory.service

import com.eventfulcommerce.inventory.repository.ReservationRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

@Component
@ConditionalOnProperty(name = ["inventory.reservations.expiry-enabled"], havingValue = "true", matchIfMissing = true)
class ReservationExpiryScheduler(
    private val reservations: ReservationRepository,
    private val service: ReservationService,
    private val clock: Clock
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${inventory.reservations.expiry-delay-ms:5000}")
    fun expireDue() {
        for (id in reservations.due(clock.instant(), PageRequest.of(0, 100))) {
            try { service.expire(id) } catch (_: Exception) { log.warn("예약 만료 재시도 필요: {}", id) }
        }
    }
}
