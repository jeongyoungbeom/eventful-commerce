package com.eventfulcommerce.user.controller

import com.eventfulcommerce.user.dto.PasswordResetDto
import com.eventfulcommerce.user.dto.PasswordResetRequestDto
import com.eventfulcommerce.user.dto.PasswordResetTokenResponse
import com.eventfulcommerce.user.dto.TokenValidationResponse
import com.eventfulcommerce.user.service.PasswordResetService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
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
        val remainingTime = passwordResetService.getRemainingTime(token)
        
        return ResponseEntity.ok(TokenValidationResponse(
            valid = true,
            remainingTime = remainingTime
        ))
    }
}
