package com.eventfulcommerce.user.filter

import com.eventfulcommerce.user.service.JwtTokenProvider
import com.eventfulcommerce.user.service.TokenBlacklistService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import kotlin.text.startsWith
import kotlin.text.substring

private val kLogger = KotlinLogging.logger {}

@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val tokenBlacklistService: TokenBlacklistService
): OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            // 1. Authorization 헤더에서 토큰 추출
            val token = resolveToken(request)

            if (token != null) {
                // 2. 토큰 검증
                if (!jwtTokenProvider.validateToken(token)) {
                    kLogger.info { "유효하지 않은 토큰" }
                    filterChain.doFilter(request, response)
                    return
                }

                // 3. Blacklist 확인 (로그아웃된 토큰)
                if (tokenBlacklistService.isBlacklisted(token)) {
                    kLogger.debug { "로그아웃된 토큰" }
                    filterChain.doFilter(request, response)
                    return
                }

                // 4. 토큰 타입 확인 (Access Token만 허용)
                val tokenType = jwtTokenProvider.getTokenType(token)
                if (tokenType != "access") {
                    kLogger.debug { "Refresh Token으로 인증 시도" }
                    filterChain.doFilter(request, response)
                    return
                }

                // 5. 사용자 정보 추출
                val userId = jwtTokenProvider.getUserId(token)
                val role = jwtTokenProvider.getRole(token)

                // 6. Spring Security 인증 객체 생성
                val authentication = UsernamePasswordAuthenticationToken(
                    userId,  // principal
                    null,    // credentials (비밀번호는 저장하지 않음)
                    listOf(SimpleGrantedAuthority("ROLE_${role.name}"))  // authorities
                )

                // 7. SecurityContext에 인증 정보 저장
                SecurityContextHolder.getContext().authentication = authentication

                kLogger.debug { "인증 성공: userId=$userId, role=$role" }
            }
        } catch (e: Exception) {
            kLogger.error(e) { "JWT 인증 필터 오류" }
        }

        filterChain.doFilter(request, response)
    }

    /**
     * Authorization 헤더에서 Bearer 토큰 추출
     */
    private fun resolveToken(request: HttpServletRequest): String? {
        val bearerToken = request.getHeader("Authorization")

        return if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken.substring(7)
        } else {
            null
        }
    }
}