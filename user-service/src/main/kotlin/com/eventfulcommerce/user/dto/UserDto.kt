package com.eventfulcommerce.user.dto

import java.time.Instant
import java.util.UUID

data class UserResponse(
    val userId: UUID,
    val email: String,
    val name: String,
    val createdAt: Instant
)

data class UserExistsResponse(
    val exists: Boolean
)
