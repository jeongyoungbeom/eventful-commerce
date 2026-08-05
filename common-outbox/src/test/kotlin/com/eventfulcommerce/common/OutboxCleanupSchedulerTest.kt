package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.OutboxEventRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant

class OutboxCleanupSchedulerTest {
    @Test
    fun `removes only sent events before the configured retention cutoff`() {
        val repository = mockk<OutboxEventRepository>()
        val properties = OutboxPublisherProperties().apply {
            sentRetentionSeconds = 60
        }
        every { repository.deleteSentBefore(any()) } returns 2

        val earliestExpectedCutoff = Instant.now().minusSeconds(60)
        OutboxCleanupScheduler(repository, properties).deleteExpiredSentEvents()
        val latestExpectedCutoff = Instant.now().minusSeconds(60)

        verify(exactly = 1) {
            repository.deleteSentBefore(match { it in earliestExpectedCutoff..latestExpectedCutoff })
        }
    }
}
