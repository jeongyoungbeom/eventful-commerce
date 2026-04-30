package com.eventfulcommerce.user.dto

import com.eventfulcommerce.user.domain.entity.UserRole
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * 일반 사용자 회원가입 요청
 */
data class SignupRequest(
    @field:Email(message = "올바른 이메일 형식이 아닙니다")
    @field:NotBlank(message = "이메일은 필수입니다")
    val email: String,
    
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@\$!%*#?&])[A-Za-z\\d@\$!%*#?&]{8,}$",
        message = "비밀번호는 최소 8자, 영문/숫자/특수문자를 포함해야 합니다"
    )
    @field:NotBlank(message = "비밀번호는 필수입니다")
    val password: String,
    
    @field:NotBlank(message = "이름은 필수입니다")
    @field:Size(min = 2, max = 50, message = "이름은 2자 이상 50자 이하여야 합니다")
    val name: String
)

/**
 * 판매자 회원가입 요청
 */
data class SellerSignupRequest(
    @field:Email(message = "올바른 이메일 형식이 아닙니다")
    @field:NotBlank(message = "이메일은 필수입니다")
    val email: String,
    
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@\$!%*#?&])[A-Za-z\\d@\$!%*#?&]{8,}$",
        message = "비밀번호는 최소 8자, 영문/숫자/특수문자를 포함해야 합니다"
    )
    @field:NotBlank(message = "비밀번호는 필수입니다")
    val password: String,
    
    @field:NotBlank(message = "이름은 필수입니다")
    @field:Size(min = 2, max = 50, message = "이름은 2자 이상 50자 이하여야 합니다")
    val name: String,
    
    @field:NotBlank(message = "사업자명은 필수입니다")
    val businessName: String,
    
    @field:NotBlank(message = "사업자 등록번호는 필수입니다")
    @field:Pattern(
        regexp = "^\\d{3}-\\d{2}-\\d{5}$",
        message = "사업자 등록번호 형식이 올바르지 않습니다 (예: 123-45-67890)"
    )
    val businessNumber: String,
    
    @field:NotBlank(message = "은행 계좌는 필수입니다")
    val bankAccount: String,
    
    @field:NotBlank(message = "은행 코드는 필수입니다")
    val bankCode: String
)

/**
 * 로그인 요청
 */
data class LoginRequest(
    @field:Email(message = "올바른 이메일 형식이 아닙니다")
    @field:NotBlank(message = "이메일은 필수입니다")
    val email: String,
    
    @field:NotBlank(message = "비밀번호는 필수입니다")
    val password: String
)

/**
 * 토큰 응답
 */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val userId: UUID,
    val role: UserRole
)

/**
 * Refresh Token 재발급 요청
 */
data class RefreshRequest(
    @field:NotBlank(message = "Refresh Token은 필수입니다")
    val refreshToken: String
)

/**
 * 회원가입 성공 응답
 */
data class SignupResponse(
    val userId: UUID,
    val email: String,
    val name: String,
    val role: UserRole
)

/**
 * 판매자 회원가입 성공 응답
 */
data class SellerSignupResponse(
    val userId: UUID,
    val sellerId: UUID,
    val email: String,
    val name: String,
    val businessName: String
)
