package com.eventfulcommerce.common.api.response

import java.time.Instant
import java.util.UUID

data class OutboxRequeueResponse(
    val eventId: UUID,
    val requeued: Boolean,
    val processedAt: Instant
)
