package com.eventfulcommerce.user.controller

import com.eventfulcommerce.user.dto.PasswordResetDto
import com.eventfulcommerce.user.dto.PasswordResetRequestDto
import com.eventfulcommerce.user.dto.PasswordResetMessageResponse
import com.eventfulcommerce.user.dto.TokenValidationResponse
import com.eventfulcommerce.user.service.PasswordResetService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auth/password")
@Tag(name = "Password Reset", description = "비밀번호 재설정 메일 발송, 재설정, 토큰 검증 API")
class PasswordResetController(
    private val passwordResetService: PasswordResetService
) {

    @PostMapping("/reset-request")
    @Operation(summary = "비밀번호 재설정 메일 요청", description = "가입 이메일로 비밀번호 재설정 링크를 전송합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재설정 이메일 발송 요청 완료"),
        ApiResponse(responseCode = "400", description = "잘못된 이메일 형식")
    )
    fun requestPasswordReset(
        @Valid @RequestBody request: PasswordResetRequestDto
    ): ResponseEntity<PasswordResetMessageResponse> {
        passwordResetService.sendResetEmail(request.email)
        return ResponseEntity.ok(PasswordResetMessageResponse("비밀번호 재설정 이메일이 전송되었습니다"))
    }

    @PostMapping("/reset")
    @Operation(summary = "비밀번호 재설정", description = "재설정 토큰과 새 비밀번호를 검증한 뒤 비밀번호를 변경합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "비밀번호 변경 성공"),
        ApiResponse(responseCode = "400", description = "토큰 또는 새 비밀번호 검증 실패")
    )
    fun resetPassword(
        @Valid @RequestBody request: PasswordResetDto,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        passwordResetService.resetPassword(request.token, request.newPassword, httpRequest)
        return ResponseEntity.ok().build()
    }

    @GetMapping("/reset/validate")
    @Operation(summary = "비밀번호 재설정 토큰 검증", description = "재설정 토큰의 유효 여부와 남은 유효 시간을 초 단위로 조회합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "검증 결과 반환")
    )
    fun validateResetToken(@RequestParam token: String): ResponseEntity<TokenValidationResponse> {
        val remainingTime = passwordResetService.getRemainingTime(token)
        return ResponseEntity.ok(TokenValidationResponse(
            valid = remainingTime > 0,
            remainingTime = remainingTime
        ))
    }
}
