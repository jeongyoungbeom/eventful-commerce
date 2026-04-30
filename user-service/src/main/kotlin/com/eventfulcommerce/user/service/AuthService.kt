package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.domain.entity.Seller
import com.eventfulcommerce.user.domain.entity.User
import com.eventfulcommerce.user.domain.entity.UserRole
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
    
    /**
     * 일반 사용자 회원가입
     */
    @Transactional
    fun signupUser(request: SignupRequest, httpRequest: HttpServletRequest?): SignupResponse {
        // 이메일 중복 체크
        if (userService.existsByEmail(request.email)) {
            throw DuplicateEmailException(request.email)
        }
        
        // User 생성
        val user = User(
            email = request.email,
            password = passwordEncoder.encode(request.password),
            name = request.name,
            role = UserRole.USER
        )
        
        val savedUser = userService.save(user)
        
        // 감사 로그
        auditLogService.logSignup(savedUser.id, httpRequest)
        
        logger.info { "✅ 일반 사용자 회원가입: userId=${savedUser.id}, email=${savedUser.email}" }
        
        return SignupResponse(
            userId = savedUser.id,
            email = savedUser.email,
            name = savedUser.name,
            role = savedUser.role
        )
    }
    
    /**
     * 판매자 회원가입
     */
    @Transactional
    fun signupSeller(request: SellerSignupRequest, httpRequest: HttpServletRequest?): SellerSignupResponse {
        // 이메일 중복 체크
        if (userService.existsByEmail(request.email)) {
            throw DuplicateEmailException(request.email)
        }
        
        // 사업자 등록번호 중복 체크
        sellerService.validateBusinessNumber(request.businessNumber)
        
        // User 생성 (SELLER 권한)
        val user = User(
            email = request.email,
            password = passwordEncoder.encode(request.password),
            name = request.name,
            role = UserRole.SELLER
        )
        
        val savedUser = userService.save(user)
        
        // Seller 정보 생성
        val seller = Seller(
            userId = savedUser.id,
            businessName = request.businessName,
            businessNumber = request.businessNumber,
            bankAccount = request.bankAccount,
            bankCode = request.bankCode
        )
        
        val savedSeller = sellerService.save(seller)
        
        // 감사 로그
        auditLogService.logSignup(savedUser.id, httpRequest)
        
        logger.info {
            "✅ 판매자 회원가입: userId=${savedUser.id}, sellerId=${savedSeller.id}, " +
            "email=${savedUser.email}, businessNumber=${savedSeller.businessNumber}"
        }
        
        return SellerSignupResponse(
            userId = savedUser.id,
            sellerId = savedSeller.id,
            email = savedUser.email,
            name = savedUser.name,
            businessName = savedSeller.businessName
        )
    }
    
    /**
     * 로그인
     */
    @Transactional
    fun login(request: LoginRequest, httpRequest: HttpServletRequest?): TokenResponse {
        val ipAddress = httpRequest?.let { getClientIP(it) } ?: "unknown"
        
        // 1. IP 기반 로그인 차단 확인
        if (loginAttemptService.isBlocked(ipAddress)) {
            val remainingTime = loginAttemptService.getRemainingLockTime(ipAddress)
            auditLogService.logLoginFailure(request.email, "IP 차단 (${remainingTime}초 남음)", httpRequest)
            throw InvalidCredentialsException()
        }
        
        // 2. 이메일 기반 로그인 차단 확인
        if (loginAttemptService.isBlockedByEmail(request.email)) {
            auditLogService.logLoginFailure(request.email, "이메일 차단", httpRequest)
            throw InvalidCredentialsException()
        }
        
        // 3. User 조회
        val user = userService.findByEmail(request.email)
        
        if (user == null) {
            // 로그인 실패 기록
            loginAttemptService.recordFailure(ipAddress)
            loginAttemptService.recordFailureByEmail(request.email)
            auditLogService.logLoginFailure(request.email, "사용자 없음", httpRequest)
            throw InvalidCredentialsException()
        }
        
        // 4. 계정 잠금 확인
        accountLockService.checkAndThrowIfLocked(user)
        
        // 5. 비밀번호 검증
        if (!passwordEncoder.matches(request.password, user.password)) {
            // 로그인 실패 기록
            loginAttemptService.recordFailure(ipAddress)
            loginAttemptService.recordFailureByEmail(request.email)
            
            // 5회 실패 시 계정 잠금
            val emailAttempts = loginAttemptService.getAttemptsByEmail(request.email)
            if (emailAttempts >= 5) {
                accountLockService.lockAccount(user.id)
                auditLogService.logLoginFailure(request.email, "5회 실패로 계정 잠금", httpRequest)
            } else {
                auditLogService.logLoginFailure(request.email, "비밀번호 불일치 (${emailAttempts}회)", httpRequest)
            }
            
            throw InvalidCredentialsException()
        }
        
        // 6. 로그인 성공 - 실패 기록 초기화
        loginAttemptService.resetAttempts(ipAddress, request.email)
        
        // 7. Access Token & Refresh Token 생성
        val accessToken = jwtTokenProvider.createAccessToken(user.id, user.role)
        val refreshToken = jwtTokenProvider.createRefreshToken(user.id)
        
        // 8. Refresh Token을 Redis에 저장
        refreshTokenService.saveRefreshToken(user.id, refreshToken)
        
        // 9. 감사 로그
        auditLogService.logLoginSuccess(user.id, httpRequest)
        
        logger.info { "✅ 로그인 성공: userId=${user.id}, email=${user.email}" }
        
        return TokenResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = user.id,
            role = user.role
        )
    }
    
    /**
     * 클라이언트 IP 추출 (AuditLogService와 동일)
     */
    private fun getClientIP(request: HttpServletRequest): String {
        val headers = listOf(
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR",
            "HTTP_X_FORWARDED",
            "HTTP_X_CLUSTER_CLIENT_IP",
            "HTTP_CLIENT_IP",
            "HTTP_FORWARDED_FOR",
            "HTTP_FORWARDED",
            "HTTP_VIA",
            "REMOTE_ADDR"
        )
        
        for (header in headers) {
            val ip = request.getHeader(header)
            if (!ip.isNullOrBlank() && ip != "unknown") {
                return ip.split(",")[0].trim()
            }
        }
        
        return request.remoteAddr ?: "unknown"
    }
    
    /**
     * Access Token 재발급
     */
    @Transactional(readOnly = true)
    fun refreshAccessToken(request: RefreshRequest): TokenResponse {
        val refreshToken = request.refreshToken
        
        // Refresh Token 검증
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw InvalidTokenException("유효하지 않은 Refresh Token입니다")
        }
        
        // 토큰 타입 확인
        val tokenType = jwtTokenProvider.getTokenType(refreshToken)
        if (tokenType != "refresh") {
            throw InvalidTokenException("Access Token으로 재발급할 수 없습니다")
        }
        
        // userId 추출
        val userId = jwtTokenProvider.getUserId(refreshToken)
        
        // Redis에 저장된 토큰과 비교
        if (!refreshTokenService.validateRefreshToken(userId, refreshToken)) {
            throw InvalidTokenException("Refresh Token이 일치하지 않습니다")
        }
        
        // User 정보 조회
        val user = userService.findById(userId)
        
        // 새로운 Access Token 생성
        val newAccessToken = jwtTokenProvider.createAccessToken(user.id, user.role)
        
        logger.info { "✅ Access Token 재발급: userId=${user.id}" }
        
        return TokenResponse(
            accessToken = newAccessToken,
            refreshToken = refreshToken,  // 기존 Refresh Token 재사용
            userId = user.id,
            role = user.role
        )
    }
    
    /**
     * 로그아웃
     */
    @Transactional
    fun logout(accessToken: String, httpRequest: HttpServletRequest?) {
        // Access Token을 Blacklist에 추가
        tokenBlacklistService.addToBlacklist(accessToken)
        
        // userId 추출
        val userId = jwtTokenProvider.getUserId(accessToken)
        
        // Refresh Token 삭제
        refreshTokenService.deleteRefreshToken(userId)
        
        // 감사 로그
        auditLogService.logLogout(userId, httpRequest)
        
        logger.info { "✅ 로그아웃: userId=$userId" }
    }
}
