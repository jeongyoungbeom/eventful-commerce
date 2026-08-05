package com.eventfulcommerce.common

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "outbox.publisher")
class OutboxPublisherProperties {
    var batchSize: Int = 50
    var maxRetries: Int = 10
    var maxInFlight: Int = 200
    var recoveryBatchSize: Int = 50
    var claimTimeoutMs: Long = 30_000
    var retryInitialDelayMs: Long = 1_000
    var retryMaxDelayMs: Long = 60_000
    var sentRetentionSeconds: Long = 604_800

    fun validate() {
        require(batchSize > 0) { "outbox.publisher.batch-size must be greater than zero" }
        require(maxRetries > 0) { "outbox.publisher.max-retries must be greater than zero" }
        require(maxInFlight > 0) { "outbox.publisher.max-in-flight must be greater than zero" }
        require(recoveryBatchSize > 0) { "outbox.publisher.recovery-batch-size must be greater than zero" }
        require(claimTimeoutMs > 0) { "outbox.publisher.claim-timeout-ms must be greater than zero" }
        require(retryInitialDelayMs > 0) { "outbox.publisher.retry-initial-delay-ms must be greater than zero" }
        require(retryMaxDelayMs >= retryInitialDelayMs) {
            "outbox.publisher.retry-max-delay-ms must be at least retry-initial-delay-ms"
        }
        require(sentRetentionSeconds > 0) { "outbox.publisher.sent-retention-seconds must be greater than zero" }
    }
}
