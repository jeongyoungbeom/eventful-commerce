package com.eventfulcommerce.user.service

import com.eventfulcommerce.common.auth.JwtTokenProvider
import com.eventfulcommerce.common.auth.UserRole
import com.eventfulcommerce.user.domain.entity.Seller
import com.eventfulcommerce.user.domain.entity.User
import com.eventfulcommerce.user.dto.*
import com.eventfulcommerce.user.exception.DuplicateEmailException
import com.eventfulcommerce.user.exception.InvalidCredentialsException
import com.eventfulcommerce.user.exception.InvalidTokenException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

@Service
class AuthService(
    private val userService: UserService,
    private val sellerService: SellerService,
    private val jwtTokenProvider: JwtTokenProvider,
    private val refreshTokenService: RefreshTokenService,
    private val tokenBlacklistService: TokenBlacklistService,
    private val auditLogService: AuditLogService,
    private val loginAttemptService: LoginAttemptService,
    private val accountLockService: AccountLockService,
    private val passwordEncoder: PasswordEncoder
) {

    @Transactional
    fun signupUser(request: SignupRequest, httpRequest: HttpServletRequest?): SignupResponse {
        if (userService.existsByEmail(request.email)) {
            throw DuplicateEmailException(request.email)
        }

        val user = User(
            email = request.email,
            password = passwordEncoder.encode(request.password),
            name = request.name
        )
        val savedUser = userService.save(user)
        auditLogService.logSignup(savedUser.id, httpRequest)
        logger.info { "일반 사용자 회원가입: userId=${savedUser.id}" }

        return SignupResponse(userId = savedUser.id, email = savedUser.email, name = savedUser.name)
    }

    @Transactional
    fun signupSeller(request: SellerSignupRequest, httpRequest: HttpServletRequest?): SellerSignupResponse {
        if (sellerService.existsByEmail(request.email)) {
            throw DuplicateEmailException(request.email)
        }
        sellerService.validateBusinessNumber(request.businessNumber)

        val seller = Seller(
            email = request.email,
            password = passwordEncoder.encode(request.password),
            name = request.name,
            businessName = request.businessName,
            businessNumber = request.businessNumber,
            bankAccount = request.bankAccount,
            bankCode = request.bankCode
        )
        val savedSeller = sellerService.save(seller)
        auditLogService.logSignup(savedSeller.id, httpRequest)
        logger.info { "판매자 회원가입: sellerId=${savedSeller.id}" }

        return SellerSignupResponse(
            sellerId = savedSeller.id,
            email = savedSeller.email,
            name = savedSeller.name,
            businessName = savedSeller.businessName
        )
    }

    @Transactional
    fun loginUser(request: LoginRequest, httpRequest: HttpServletRequest?): TokenResponse {
        val ipAddress = getClientIP(httpRequest)

        checkIpAndEmailBlock(request.email, ipAddress, httpRequest)

        val user = userService.findByEmail(request.email) ?: run {
            recordFailureAndThrow(ipAddress, request.email, "사용자 없음", httpRequest)
        }

        accountLockService.checkAndThrowIfLocked(user)

        if (!passwordEncoder.matches(request.password, user.password)) {
            recordFailureWithLockCheck(user, ipAddress, request.email, httpRequest)
        }

        loginAttemptService.resetAttempts(ipAddress, request.email)
        val accessToken = jwtTokenProvider.createAccessToken(user.id, UserRole.USER)
        val refreshToken = jwtTokenProvider.createRefreshToken(user.id, UserRole.USER)
        refreshTokenService.saveRefreshToken(user.id, refreshToken, UserRole.USER)
        auditLogService.logLoginSuccess(user.id, httpRequest)
        logger.info { "사용자 로그인 성공: userId=${user.id}" }

        return TokenResponse(accessToken = accessToken, refreshToken = refreshToken, userId = user.id, role = UserRole.USER)
    }

    @Transactional
    fun loginSeller(request: LoginRequest, httpRequest: HttpServletRequest?): TokenResponse {
        val ipAddress = getClientIP(httpRequest)

        checkIpAndEmailBlock(request.email, ipAddress, httpRequest)

        val seller = sellerService.findByEmail(request.email) ?: run {
            recordFailureAndThrow(ipAddress, request.email, "판매자 없음", httpRequest)
        }

        accountLockService.checkAndThrowIfLocked(seller)

        if (!passwordEncoder.matches(request.password, seller.password)) {
            recordFailureWithLockCheck(seller, ipAddress, request.email, httpRequest)
        }

        loginAttemptService.resetAttempts(ipAddress, request.email)
        val accessToken = jwtTokenProvider.createAccessToken(seller.id, UserRole.SELLER)
        val refreshToken = jwtTokenProvider.createRefreshToken(seller.id, UserRole.SELLER)
        refreshTokenService.saveRefreshToken(seller.id, refreshToken, UserRole.SELLER)
        auditLogService.logLoginSuccess(seller.id, httpRequest)
        logger.info { "판매자 로그인 성공: sellerId=${seller.id}" }

        return TokenResponse(accessToken = accessToken, refreshToken = refreshToken, userId = seller.id, role = UserRole.SELLER)
    }

    @Transactional(readOnly = true)
    fun refreshAccessToken(request: RefreshRequest): TokenResponse {
        val refreshToken = request.refreshToken

        if (!jwtTokenProvider.validateToken(refreshToken)) throw InvalidTokenException("유효하지 않은 Refresh Token입니다")
        if (jwtTokenProvider.getTokenType(refreshToken) != "refresh") throw InvalidTokenException("Access Token으로 재발급할 수 없습니다")

        val id = jwtTokenProvider.getUserId(refreshToken)
        val role = jwtTokenProvider.getRole(refreshToken)

        if (!refreshTokenService.validateRefreshToken(id, refreshToken, role)) {
            throw InvalidTokenException("Refresh Token이 일치하지 않습니다")
        }

        val newAccessToken = jwtTokenProvider.createAccessToken(id, role)
        logger.info { "Access Token 재발급: id=$id, role=$role" }

        return TokenResponse(accessToken = newAccessToken, refreshToken = refreshToken, userId = id, role = role)
    }

    @Transactional
    fun logout(accessToken: String, httpRequest: HttpServletRequest?) {
        tokenBlacklistService.addToBlacklist(accessToken)
        val id = jwtTokenProvider.getUserId(accessToken)
        val role = jwtTokenProvider.getRole(accessToken)
        refreshTokenService.deleteRefreshToken(id, role)
        auditLogService.logLogout(id, httpRequest)
        logger.info { "로그아웃: id=$id, role=$role" }
    }

    private fun checkIpAndEmailBlock(email: String, ipAddress: String, httpRequest: HttpServletRequest?) {
        if (loginAttemptService.isBlocked(ipAddress)) {
            val remaining = loginAttemptService.getRemainingLockTime(ipAddress)
            auditLogService.logLoginFailure(email, "IP 차단 (${remaining}초 남음)", httpRequest)
            throw InvalidCredentialsException()
        }
        if (loginAttemptService.isBlockedByEmail(email)) {
            auditLogService.logLoginFailure(email, "이메일 차단", httpRequest)
            throw InvalidCredentialsException()
        }
    }

    private fun recordFailureAndThrow(ipAddress: String, email: String, reason: String, httpRequest: HttpServletRequest?): Nothing {
        loginAttemptService.recordFailure(ipAddress)
        loginAttemptService.recordFailureByEmail(email)
        auditLogService.logLoginFailure(email, reason, httpRequest)
        throw InvalidCredentialsException()
    }

    private fun recordFailureWithLockCheck(lockable: com.eventfulcommerce.user.domain.entity.Lockable, ipAddress: String, email: String, httpRequest: HttpServletRequest?): Nothing {
        loginAttemptService.recordFailure(ipAddress)
        loginAttemptService.recordFailureByEmail(email)
        val attempts = loginAttemptService.getAttemptsByEmail(email)
        if (attempts >= 5) {
            accountLockService.lock(lockable)
            auditLogService.logLoginFailure(email, "5회 실패로 계정 잠금", httpRequest)
        } else {
            auditLogService.logLoginFailure(email, "비밀번호 불일치 (${attempts}회)", httpRequest)
        }
        throw InvalidCredentialsException()
    }

    private fun getClientIP(request: HttpServletRequest?): String {
        if (request == null) return "unknown"
        val headers = listOf(
            "X-Forwarded-For", "Proxy-Client-IP", "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR", "HTTP_CLIENT_IP", "HTTP_FORWARDED_FOR", "REMOTE_ADDR"
        )
        for (header in headers) {
            val ip = request.getHeader(header)
            if (!ip.isNullOrBlank() && ip != "unknown") return ip.split(",")[0].trim()
        }
        return request.remoteAddr ?: "unknown"
    }
}
