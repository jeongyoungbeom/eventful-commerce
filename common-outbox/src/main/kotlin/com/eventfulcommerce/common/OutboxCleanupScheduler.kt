package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.OutboxEventRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val outboxCleanupLogger = KotlinLogging.logger { }

@Component
class OutboxCleanupScheduler(
    private val outboxEventRepository: OutboxEventRepository,
    private val properties: OutboxPublisherProperties
) {
    @Scheduled(fixedDelayString = "\${outbox.publisher.cleanup-fixed-delay-ms:3600000}")
    @Transactional
    fun deleteExpiredSentEvents() {
        properties.validate()
        val cutoff = Instant.now().minusSeconds(properties.sentRetentionSeconds)
        val deleted = outboxEventRepository.deleteSentBefore(cutoff)
        if (deleted > 0) {
            outboxCleanupLogger.info { "Deleted sent outbox events older than $cutoff: count=$deleted" }
        }
    }
}
