package com.eventfulcommerce.common.api.response

data class DeadLetterEventListResponse(
    val events: List<DeadLetterEventResponse>
)
