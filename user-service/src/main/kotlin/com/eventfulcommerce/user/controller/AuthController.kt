package com.eventfulcommerce.user.controller

import com.eventfulcommerce.user.dto.*
import com.eventfulcommerce.user.service.AuthService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService
) {
    
    /**
     * 일반 사용자 회원가입
     */
    @PostMapping("/signup/user")
    fun signupUser(
        @Valid @RequestBody request: SignupRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SignupResponse> {
        val response = authService.signupUser(request, httpRequest)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
    
    /**
     * 판매자 회원가입
     */
    @PostMapping("/signup/seller")
    fun signupSeller(
        @Valid @RequestBody request: SellerSignupRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SellerSignupResponse> {
        val response = authService.signupSeller(request, httpRequest)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
    
    /**
     * 로그인
     */
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<TokenResponse> {
        val response = authService.login(request, httpRequest)
        return ResponseEntity.ok(response)
    }
    
    /**
     * Access Token 재발급
     */
    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest
    ): ResponseEntity<TokenResponse> {
        val response = authService.refreshAccessToken(request)
        return ResponseEntity.ok(response)
    }
    
    /**
     * 로그아웃
     */
    @PostMapping("/logout")
    fun logout(
        @RequestHeader("Authorization") authorization: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        // Bearer 토큰 추출
        val accessToken = if (authorization.startsWith("Bearer ")) {
            authorization.substring(7)
        } else {
            throw IllegalArgumentException("Invalid Authorization header")
        }
        
        authService.logout(accessToken, httpRequest)
        return ResponseEntity.noContent().build()
    }
}
