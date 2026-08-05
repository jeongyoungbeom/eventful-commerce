package com.eventfulcommerce.common.metrics

import com.eventfulcommerce.common.DeadLetterEventStatus
import com.eventfulcommerce.common.repository.DeadLetterEventRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Component

@Component
@ConditionalOnBean(MeterRegistry::class)
class DeadLetterEventMetrics(
    private val deadLetterEventRepository: DeadLetterEventRepository,
    meterRegistry: MeterRegistry
) {
    init {
        DeadLetterEventStatus.entries.forEach { status ->
            Gauge.builder("eventful.dlt.events") {
                deadLetterEventRepository.countByStatus(status).toDouble()
            }
                .description("Number of dead-letter events by replay status")
                .tag("status", status.name)
                .register(meterRegistry)
        }
    }
}
