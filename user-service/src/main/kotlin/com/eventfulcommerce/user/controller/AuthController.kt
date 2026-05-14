package com.eventfulcommerce.user.controller

import com.eventfulcommerce.user.dto.*
import com.eventfulcommerce.user.service.AuthService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "구매자/판매자 회원가입, 로그인, 토큰 갱신, 로그아웃 API")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/signup/user")
    @Operation(summary = "구매자 회원가입", description = "이메일, 비밀번호, 이름으로 구매자 계정을 생성합니다. 비밀번호는 영문, 숫자, 특수문자를 포함한 8자 이상이어야 합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "구매자 회원가입 성공"),
        ApiResponse(responseCode = "400", description = "요청 값 검증 실패 또는 중복 이메일")
    )
    fun signupUser(
        @Valid @RequestBody request: SignupRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SignupResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.signupUser(request, httpRequest))

    @PostMapping("/signup/seller")
    @Operation(summary = "판매자 회원가입", description = "판매자 계정과 사업자 정보를 함께 등록합니다. 사업자 등록번호는 123-45-67890 형식이어야 합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "판매자 회원가입 성공"),
        ApiResponse(responseCode = "400", description = "요청 값 검증 실패 또는 중복 이메일/사업자 정보")
    )
    fun signupSeller(
        @Valid @RequestBody request: SellerSignupRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SellerSignupResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.signupSeller(request, httpRequest))

    @PostMapping("/login/user")
    @Operation(summary = "구매자 로그인", description = "구매자 이메일과 비밀번호로 로그인하고 accessToken, refreshToken을 발급합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "로그인 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 로그인 요청"),
        ApiResponse(responseCode = "401", description = "인증 실패")
    )
    fun loginUser(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(authService.loginUser(request, httpRequest))

    @PostMapping("/login/seller")
    @Operation(summary = "판매자 로그인", description = "판매자 이메일과 비밀번호로 로그인하고 accessToken, refreshToken을 발급합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "로그인 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 로그인 요청"),
        ApiResponse(responseCode = "401", description = "인증 실패")
    )
    fun loginSeller(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(authService.loginSeller(request, httpRequest))

    @PostMapping("/refresh")
    @Operation(summary = "Access Token 재발급", description = "유효한 refreshToken으로 새 accessToken과 refreshToken을 발급합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "토큰 재발급 성공"),
        ApiResponse(responseCode = "400", description = "Refresh Token 누락 또는 형식 오류"),
        ApiResponse(responseCode = "401", description = "만료되었거나 유효하지 않은 Refresh Token")
    )
    fun refresh(
        @Valid @RequestBody request: RefreshRequest
    ): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(authService.refreshAccessToken(request))

    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "로그아웃", description = "현재 accessToken을 블랙리스트 처리하고 refreshToken 세션을 정리합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "로그아웃 성공"),
        ApiResponse(responseCode = "400", description = "Authorization 헤더 형식 오류"),
        ApiResponse(responseCode = "401", description = "인증 실패")
    )
    fun logout(
        @RequestHeader("Authorization") authorization: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        val accessToken = if (authorization.startsWith("Bearer ")) authorization.substring(7)
        else throw IllegalArgumentException("Invalid Authorization header")
        authService.logout(accessToken, httpRequest)
        return ResponseEntity.noContent().build()
    }
}
