package com.eventfulcommerce.order.dto

import java.time.Instant

data class ErrorResponse(
    val code: String,
    val message: String,
    val details: Any? = null,
    val timestamp: Instant
)
