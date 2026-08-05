package com.eventfulcommerce.common

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class OutboxRetryPolicyTest {
    @Test
    fun `retryCount에 따라 지수 백오프를 적용하고 최대 지연을 넘지 않는다`() {
        val properties = OutboxPublisherProperties().apply {
            retryInitialDelayMs = 1_000
            retryMaxDelayMs = 5_000
        }
        val policy = OutboxRetryPolicy(properties)
        val now = Instant.parse("2026-08-05T00:00:00Z")

        assertEquals(now.plusMillis(1_000), policy.nextAttemptAt(1, now))
        assertEquals(now.plusMillis(2_000), policy.nextAttemptAt(2, now))
        assertEquals(now.plusMillis(4_000), policy.nextAttemptAt(3, now))
        assertEquals(now.plusMillis(5_000), policy.nextAttemptAt(4, now))
        assertEquals(now.plusMillis(5_000), policy.nextAttemptAt(20, now))
    }
}
