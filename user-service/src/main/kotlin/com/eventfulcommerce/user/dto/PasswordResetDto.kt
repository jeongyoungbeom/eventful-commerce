package com.eventfulcommerce.user.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * 비밀번호 재설정 요청 DTO
 */
data class PasswordResetRequestDto(
    @field:Email(message = "올바른 이메일 형식이 아닙니다")
    @field:NotBlank(message = "이메일은 필수입니다")
    val email: String
)

/**
 * 비밀번호 재설정 DTO
 */
data class PasswordResetDto(
    @field:NotBlank(message = "토큰은 필수입니다")
    val token: String,
    
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@\$!%*#?&])[A-Za-z\\d@\$!%*#?&]{8,}$",
        message = "비밀번호는 최소 8자, 영문/숫자/특수문자를 포함해야 합니다"
    )
    @field:NotBlank(message = "새 비밀번호는 필수입니다")
    val newPassword: String
)

/**
 * 비밀번호 재설정 토큰 응답
 */
data class PasswordResetTokenResponse(
    val message: String,
    val token: String  // 개발 환경에서만 (프로덕션에서는 제거)
)

/**
 * 토큰 검증 응답
 */
data class TokenValidationResponse(
    val valid: Boolean,
    val remainingTime: Long
)
