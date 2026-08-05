package com.eventfulcommerce.common.api.response

import java.util.UUID

data class DeadLetterReplayResponse(
    val eventId: UUID,
    val replayed: Boolean
)
