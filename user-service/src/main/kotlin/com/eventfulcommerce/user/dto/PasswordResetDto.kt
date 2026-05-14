package com.eventfulcommerce.user.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 비밀번호 재설정 요청 DTO
 */
@Schema(description = "비밀번호 재설정 메일 요청")
data class PasswordResetRequestDto(
    @field:Schema(description = "비밀번호 재설정 메일을 받을 가입 이메일", example = "buyer@example.com", required = true)
    @field:Email(message = "올바른 이메일 형식이 아닙니다")
    @field:NotBlank(message = "이메일은 필수입니다")
    val email: String
)

/**
 * 비밀번호 재설정 DTO
 */
@Schema(description = "비밀번호 재설정 요청")
data class PasswordResetDto(
    @field:Schema(description = "메일 링크로 전달된 재설정 토큰", required = true)
    @field:NotBlank(message = "토큰은 필수입니다")
    val token: String,
    
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@\$!%*#?&])[A-Za-z\\d@\$!%*#?&]{8,}$",
        message = "비밀번호는 최소 8자, 영문/숫자/특수문자를 포함해야 합니다"
    )
    @field:Schema(description = "새 비밀번호. 최소 8자, 영문/숫자/특수문자 포함", example = "Newpass123!", required = true)
    @field:NotBlank(message = "새 비밀번호는 필수입니다")
    val newPassword: String
)

/**
 * 비밀번호 재설정 메시지 응답
 */
@Schema(description = "메시지 응답")
data class PasswordResetMessageResponse(
    val message: String
)

/**
 * 토큰 검증 응답
 */
@Schema(description = "토큰 검증 응답")
data class TokenValidationResponse(
    val valid: Boolean,
    val remainingTime: Long
)
