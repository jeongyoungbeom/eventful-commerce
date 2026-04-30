package com.eventfulcommerce.user.controller

import com.eventfulcommerce.user.service.PasswordResetService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auth/password")
class PasswordResetController(
    private val passwordResetService: PasswordResetService
) {
    
    /**
     * 비밀번호 재설정 요청 (이메일로 토큰 전송)
     */
    @PostMapping("/reset-request")
    fun requestPasswordReset(
        @Valid @RequestBody request: PasswordResetRequestDto
    ): ResponseEntity<PasswordResetTokenResponse> {
        val token = passwordResetService.createResetToken(request.email)
        
        return ResponseEntity.ok(PasswordResetTokenResponse(
            message = "비밀번호 재설정 이메일이 전송되었습니다",
            token = token  // 개발 환경에서만 반환 (프로덕션에서는 제거)
        ))
    }
    
    /**
     * 비밀번호 재설정 (토큰 검증 후 변경)
     */
    @PostMapping("/reset")
    fun resetPassword(
        @Valid @RequestBody request: PasswordResetDto,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        passwordResetService.resetPassword(request.token, request.newPassword, httpRequest)
        return ResponseEntity.ok().build()
    }
    
    /**
     * 비밀번호 재설정 토큰 검증
     */
    @GetMapping("/reset/validate")
    fun validateResetToken(@RequestParam token: String): ResponseEntity<TokenValidationResponse> {
        val userId = passwordResetService.validateResetToken(token)
        val remainingTime = passwordResetService.getRemainingTime(token)
        
        return ResponseEntity.ok(TokenValidationResponse(
            valid = true,
            remainingTime = remainingTime
        ))
    }
}

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
