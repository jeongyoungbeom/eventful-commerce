package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.ProcessedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class ProcessedEventCleanupScheduler(
    private val processedEventRepository: ProcessedEventRepository,
    @Value("\${idempotency.processed-event-retention-days:30}")
    private val retentionDays: Long = 30
) {
    init {
        require(retentionDays > 0) { "processed event retentionDays must be greater than zero" }
    }

    /**
     * Retention must remain longer than the Kafka replay window plus the longest expected outage.
     */
    @Scheduled(cron = "\${idempotency.cleanup-cron:0 17 3 * * *}")
    @Transactional
    fun cleanup() {
        val cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS)
        val deleted = processedEventRepository.deleteProcessedBefore(cutoff)
        if (deleted > 0) {
            logger.info("Deleted {} processed event tombstones older than {}", deleted, cutoff)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ProcessedEventCleanupScheduler::class.java)
    }
}
