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

    @PostMapping("/signup/user")
    fun signupUser(
        @Valid @RequestBody request: SignupRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SignupResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.signupUser(request, httpRequest))

    @PostMapping("/signup/seller")
    fun signupSeller(
        @Valid @RequestBody request: SellerSignupRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SellerSignupResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.signupSeller(request, httpRequest))

    @PostMapping("/login/user")
    fun loginUser(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(authService.loginUser(request, httpRequest))

    @PostMapping("/login/seller")
    fun loginSeller(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(authService.loginSeller(request, httpRequest))

    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest
    ): ResponseEntity<TokenResponse> =
        ResponseEntity.ok(authService.refreshAccessToken(request))

    @PostMapping("/logout")
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
