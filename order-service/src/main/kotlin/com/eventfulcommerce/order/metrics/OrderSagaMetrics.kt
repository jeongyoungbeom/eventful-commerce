package com.eventfulcommerce.order.metrics

import com.eventfulcommerce.order.domain.entity.OrderSagaStatus
import com.eventfulcommerce.order.repository.OrderSagaRepository
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class OrderSagaMetrics(
    private val orderSagaRepository: OrderSagaRepository,
    meterRegistry: MeterRegistry
) {
    init {
        OrderSagaStatus.entries.forEach { status ->
            Gauge.builder("eventful.saga.orders") {
                orderSagaRepository.countByStatus(status).toDouble()
            }
                .description("Number of orders by saga status")
                .tag("status", status.name)
                .register(meterRegistry)
        }
    }
}
