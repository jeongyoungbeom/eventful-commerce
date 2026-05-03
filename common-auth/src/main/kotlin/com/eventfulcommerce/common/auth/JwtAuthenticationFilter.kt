package com.eventfulcommerce.common.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

private val logger = KotlinLogging.logger {}

open class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        try {
            val token = resolveToken(request)

            if (token != null) {
                if (!jwtTokenProvider.validateToken(token)) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid token")
                    return
                }

                // 서비스별 추가 검증 (블랙리스트 등)
                if (!additionalValidation(token, response)) return

                if (jwtTokenProvider.getTokenType(token) != "access") {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Access token required")
                    return
                }

                val userId = jwtTokenProvider.getUserId(token)
                val role = jwtTokenProvider.getRole(token)

                SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
                )

                logger.debug { "Authenticated: userId=$userId, role=$role" }
            }
        } catch (e: Exception) {
            logger.error { "JWT filter error: ${e.message}" }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication error")
            return
        }

        filterChain.doFilter(request, response)
    }

    // 서브클래스에서 블랙리스트 등 추가 검증 구현. false 반환 시 요청 차단.
    protected open fun additionalValidation(token: String, response: HttpServletResponse): Boolean = true

    private fun resolveToken(request: HttpServletRequest): String? {
        val bearer = request.getHeader("Authorization") ?: return null
        return if (bearer.startsWith("Bearer ")) bearer.substring(7) else null
    }
}
