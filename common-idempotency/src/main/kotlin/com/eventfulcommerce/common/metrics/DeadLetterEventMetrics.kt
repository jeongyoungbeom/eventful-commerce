package com.eventfulcommerce.common.metrics

import com.eventfulcommerce.common.DeadLetterEventStatus
import com.eventfulcommerce.common.repository.DeadLetterEventRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

@Component
class DeadLetterEventMetrics(
    private val deadLetterEventRepository: DeadLetterEventRepository,
    meterRegistryProvider: ObjectProvider<MeterRegistry>
) {
    init {
        meterRegistryProvider.ifAvailable { meterRegistry ->
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
}
