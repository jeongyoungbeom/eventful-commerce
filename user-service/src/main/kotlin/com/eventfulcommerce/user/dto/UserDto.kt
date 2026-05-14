package com.eventfulcommerce.user.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "사용자 프로필 응답")
data class UserResponse(
    val userId: UUID,
    val email: String,
    val name: String,
    val createdAt: Instant
)
