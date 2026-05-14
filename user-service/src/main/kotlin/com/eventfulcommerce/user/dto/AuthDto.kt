package com.eventfulcommerce.user.dto

import com.eventfulcommerce.common.auth.UserRole
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

@Schema(description = "구매자 회원가입 요청")
data class SignupRequest(
    @field:Schema(description = "로그인 이메일", example = "buyer@example.com", required = true)
    @field:Email(message = "올바른 이메일 형식이 아닙니다")
    @field:NotBlank(message = "이메일은 필수입니다")
    val email: String,

    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@\$!%*#?&])[A-Za-z\\d@\$!%*#?&]{8,}$",
        message = "비밀번호는 최소 8자, 영문/숫자/특수문자를 포함해야 합니다"
    )
    @field:Schema(description = "비밀번호. 최소 8자, 영문/숫자/특수문자 포함", example = "Test1234!", required = true)
    @field:NotBlank(message = "비밀번호는 필수입니다")
    val password: String,

    @field:Schema(description = "사용자 이름", example = "홍길동", required = true)
    @field:NotBlank(message = "이름은 필수입니다")
    @field:Size(min = 2, max = 50, message = "이름은 2자 이상 50자 이하여야 합니다")
    val name: String
)

@Schema(description = "판매자 회원가입 요청")
data class SellerSignupRequest(
    @field:Schema(description = "로그인 이메일", example = "seller@example.com", required = true)
    @field:Email(message = "올바른 이메일 형식이 아닙니다")
    @field:NotBlank(message = "이메일은 필수입니다")
    val email: String,

    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@\$!%*#?&])[A-Za-z\\d@\$!%*#?&]{8,}$",
        message = "비밀번호는 최소 8자, 영문/숫자/특수문자를 포함해야 합니다"
    )
    @field:Schema(description = "비밀번호. 최소 8자, 영문/숫자/특수문자 포함", example = "Test1234!", required = true)
    @field:NotBlank(message = "비밀번호는 필수입니다")
    val password: String,

    @field:Schema(description = "대표자 또는 담당자 이름", example = "김판매", required = true)
    @field:NotBlank(message = "이름은 필수입니다")
    @field:Size(min = 2, max = 50, message = "이름은 2자 이상 50자 이하여야 합니다")
    val name: String,

    @field:Schema(description = "사업자명", example = "이벤트풀 플라워", required = true)
    @field:NotBlank(message = "사업자명은 필수입니다")
    val businessName: String,

    @field:Schema(description = "사업자 등록번호", example = "123-45-67890", required = true)
    @field:NotBlank(message = "사업자 등록번호는 필수입니다")
    @field:Pattern(
        regexp = "^\\d{3}-\\d{2}-\\d{5}$",
        message = "사업자 등록번호 형식이 올바르지 않습니다 (예: 123-45-67890)"
    )
    val businessNumber: String,

    @field:Schema(description = "정산 계좌번호", example = "110-123-456789", required = true)
    @field:NotBlank(message = "은행 계좌는 필수입니다")
    val bankAccount: String,

    @field:Schema(description = "은행 코드", example = "088", required = true)
    @field:NotBlank(message = "은행 코드는 필수입니다")
    val bankCode: String
)

@Schema(description = "로그인 요청")
data class LoginRequest(
    @field:Schema(description = "이메일", example = "buyer@example.com", required = true)
    @field:Email(message = "올바른 이메일 형식이 아닙니다")
    @field:NotBlank(message = "이메일은 필수입니다")
    val email: String,

    @field:Schema(description = "비밀번호", example = "Test1234!", required = true)
    @field:NotBlank(message = "비밀번호는 필수입니다")
    val password: String
)

@Schema(description = "토큰 응답")
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val userId: UUID,
    val role: UserRole
)

@Schema(description = "Access Token 재발급 요청")
data class RefreshRequest(
    @field:Schema(description = "Refresh Token", required = true)
    @field:NotBlank(message = "Refresh Token은 필수입니다")
    val refreshToken: String
)

@Schema(description = "구매자 회원가입 응답")
data class SignupResponse(
    val userId: UUID,
    val email: String,
    val name: String
)

@Schema(description = "판매자 회원가입 응답")
data class SellerSignupResponse(
    val sellerId: UUID,
    val email: String,
    val name: String,
    val businessName: String
)
