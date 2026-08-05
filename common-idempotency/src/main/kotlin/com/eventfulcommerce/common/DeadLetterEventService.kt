package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.DeadLetterEventRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@Component
class DeadLetterEventService(
    private val deadLetterEventRepository: DeadLetterEventRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>
) {
    @Transactional
    fun archive(record: ConsumerRecord<String, String>) {
        val originalTopic = headerText(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)
            ?: record.topic().removeSuffix(".DLT")
        val originalPartition = headerInt(record, KafkaHeaders.DLT_ORIGINAL_PARTITION) ?: record.partition()
        val originalOffset = headerLong(record, KafkaHeaders.DLT_ORIGINAL_OFFSET) ?: record.offset()
        if (deadLetterEventRepository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(
                originalTopic,
                originalPartition,
                originalOffset
            )
        ) return

        deadLetterEventRepository.save(
            DeadLetterEvent(
                originalTopic = originalTopic,
                originalPartition = originalPartition,
                originalOffset = originalOffset,
                recordKey = record.key(),
                payload = requireNotNull(record.value()) { "DLT payload must not be null" },
                exceptionClass = headerText(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                exceptionMessage = headerText(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE)?.take(2_000)
            )
        )
    }

    @Transactional(readOnly = true)
    fun findPending(limit: Int): List<DeadLetterEvent> =
        deadLetterEventRepository.findByStatusOrderByReceivedAtAsc(
            DeadLetterEventStatus.PENDING,
            PageRequest.of(0, limit.coerceIn(1, MAX_LIST_LIMIT))
        )

    /**
     * The row lock prevents two operators from publishing the same DLT record concurrently.
     * A process crash after Kafka accepts the record can replay it once more; consumer idempotency
     * is intentionally the final protection for that at-least-once case.
     */
    @Transactional
    fun replay(eventId: UUID): Boolean {
        val event = deadLetterEventRepository.findByIdForUpdate(eventId) ?: return false
        if (event.status == DeadLetterEventStatus.REPLAYED) return false

        if (event.recordKey == null) {
            kafkaTemplate.send(event.originalTopic, event.payload).get()
        } else {
            kafkaTemplate.send(event.originalTopic, event.recordKey, event.payload).get()
        }
        event.status = DeadLetterEventStatus.REPLAYED
        event.replayCount += 1
        event.lastReplayedAt = Instant.now()
        return true
    }

    private fun headerText(record: ConsumerRecord<String, String>, headerName: String): String? =
        record.headers().lastHeader(headerName)?.value()?.toString(StandardCharsets.UTF_8)

    private fun headerInt(record: ConsumerRecord<String, String>, headerName: String): Int? =
        record.headers().lastHeader(headerName)?.value()?.let { ByteBuffer.wrap(it).int }

    private fun headerLong(record: ConsumerRecord<String, String>, headerName: String): Long? =
        record.headers().lastHeader(headerName)?.value()?.let { ByteBuffer.wrap(it).long }

    private companion object {
        const val MAX_LIST_LIMIT = 200
    }
}
