package com.eventfulcommerce.common.api.response

import com.eventfulcommerce.common.OutboxEvent
import java.time.Instant
import java.util.UUID

data class OutboxFailedEventResponse(
    val eventId: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val retryCount: Int,
    val requeueCount: Int,
    val lastError: String?,
    val failedAt: Instant?,
    val createdAt: Instant
) {
    companion object {
        fun from(event: OutboxEvent) = OutboxFailedEventResponse(
            eventId = event.id,
            aggregateType = event.aggregateType,
            aggregateId = event.aggregateId,
            eventType = event.eventType,
            retryCount = event.retryCount,
            requeueCount = event.requeueCount,
            lastError = event.lastError,
            failedAt = event.failedAt,
            createdAt = event.createdAt
        )
    }
}
