package com.eventfulcommerce.common

import org.springframework.stereotype.Component
import java.time.Instant

@Component
class OutboxRetryPolicy(
    private val properties: OutboxPublisherProperties
) {
    fun nextAttemptAt(retryCount: Int, now: Instant): Instant {
        require(retryCount > 0) { "retryCount must be greater than zero" }
        properties.validate()

        var delay = properties.retryInitialDelayMs
        repeat((retryCount - 1).coerceAtMost(30)) {
            delay = if (delay >= properties.retryMaxDelayMs / 2) {
                properties.retryMaxDelayMs
            } else {
                delay * 2
            }
        }
        return now.plusMillis(delay.coerceAtMost(properties.retryMaxDelayMs))
    }
}
