package com.eventfulcommerce.common.api.response

data class OutboxFailedEventsResponse(
    val events: List<OutboxFailedEventResponse>
)
