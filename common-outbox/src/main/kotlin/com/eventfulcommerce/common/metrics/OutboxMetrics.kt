package com.eventfulcommerce.common.metrics

import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.repository.OutboxEventRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class OutboxMetrics(
    private val outboxEventRepository: OutboxEventRepository,
    private val meterRegistry: MeterRegistry
) {
    init {
        listOf(OutboxStatus.PENDING, OutboxStatus.FAILED).forEach { status ->
            Gauge.builder("eventful.outbox.events") {
                outboxEventRepository.countByStatus(status).toDouble()
            }
                .description("Number of outbox events by status")
                .tag("status", status.name)
                .register(meterRegistry)
        }
    }
}
