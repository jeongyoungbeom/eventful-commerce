package com.eventfulcommerce.common.api.response

import com.eventfulcommerce.common.DeadLetterEvent
import com.eventfulcommerce.common.DeadLetterEventStatus
import java.time.Instant
import java.util.UUID

data class DeadLetterEventResponse(
    val eventId: UUID,
    val originalTopic: String,
    val originalPartition: Int,
    val originalOffset: Long,
    val recordKey: String?,
    val exceptionClass: String?,
    val exceptionMessage: String?,
    val status: DeadLetterEventStatus,
    val replayCount: Int,
    val receivedAt: Instant,
    val lastReplayedAt: Instant?
) {
    companion object {
        fun from(event: DeadLetterEvent) = DeadLetterEventResponse(
            event.id, event.originalTopic, event.originalPartition, event.originalOffset,
            event.recordKey, event.exceptionClass, event.exceptionMessage, event.status,
            event.replayCount, event.receivedAt, event.lastReplayedAt
        )
    }
}
