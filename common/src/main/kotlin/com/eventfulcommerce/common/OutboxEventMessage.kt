package com.eventfulcommerce.common

import java.time.Instant
import java.util.UUID

data class OutboxEventMessage(
    val eventId: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val occurredAt: Instant,
    val payload: String
)