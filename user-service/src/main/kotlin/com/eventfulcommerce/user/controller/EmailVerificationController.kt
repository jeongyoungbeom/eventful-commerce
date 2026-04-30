package com.eventfulcommerce.user.controller

import com.eventfulcommerce.user.service.EmailVerificationService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auth/email")
class EmailVerificationController(
    private val emailVerificationService: EmailVerificationService
) {
    
    /**
     * 이메일 인증 처리
     */
    @PostMapping("/verify")
    fun verifyEmail(@RequestParam token: String): ResponseEntity<VerificationResponse> {
        emailVerificationService.verifyEmail(token)
        
        return ResponseEntity.ok(VerificationResponse(
            message = "이메일 인증이 완료되었습니다"
        ))
    }
    
    /**
     * 인증 이메일 재전송
     */
    @PostMapping("/resend")
    fun resendVerificationEmail(
        @Valid @RequestBody request: EmailResendRequest
    ): ResponseEntity<VerificationTokenResponse> {
        val token = emailVerificationService.resendVerificationToken(request.email)
        
        return ResponseEntity.ok(VerificationTokenResponse(
            message = "인증 이메일이 재전송되었습니다",
            token = token  // 개발 환경에서만 반환 (프로덕션에서는 제거)
        ))
    }
    
    /**
     * 인증 토큰 검증
     */
    @GetMapping("/verify/validate")
    fun validateVerificationToken(@RequestParam token: String): ResponseEntity<TokenValidationResponse> {
        val remainingTime = emailVerificationService.getRemainingTime(token)
        
        return ResponseEntity.ok(TokenValidationResponse(
            valid = remainingTime > 0,
            remainingTime = remainingTime
        ))
    }
}

/**
 * 이메일 재전송 요청 DTO
 */
data class EmailResendRequest(
    @field:Email(message = "올바른 이메일 형식이 아닙니다")
    @field:NotBlank(message = "이메일은 필수입니다")
    val email: String
)

/**
 * 인증 응답
 */
data class VerificationResponse(
    val message: String
)

/**
 * 인증 토큰 응답
 */
data class VerificationTokenResponse(
    val message: String,
    val token: String  // 개발 환경에서만 (프로덕션에서는 제거)
)
