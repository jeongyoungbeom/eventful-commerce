package com.eventfulcommerce.common.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class EventfulBusinessMetrics(
    private val meterRegistry: MeterRegistry
) {
    private val counters = ConcurrentHashMap<Pair<String, String>, Counter>()

    fun increment(flow: String, result: String) {
        counters.computeIfAbsent(flow to result) {
            Counter.builder("eventful.business.events")
                .description("Business-level events in Eventful Commerce")
                .tag("flow", flow)
                .tag("result", result)
                .register(meterRegistry)
        }.increment()
    }
}
