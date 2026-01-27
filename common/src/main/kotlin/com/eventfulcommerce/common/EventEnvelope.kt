package com.eventfulcommerce.common

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class EventEnvelope(
    val eventId: UUID,
    val eventType: String,
    val occurredAt: Instant,
    val correlationId: String,
    val producer: String,
    val payload: JsonNode,
)
