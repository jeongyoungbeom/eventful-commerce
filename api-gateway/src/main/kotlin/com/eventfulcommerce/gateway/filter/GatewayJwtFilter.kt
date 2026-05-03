package com.eventfulcommerce.gateway.filter

import com.eventfulcommerce.common.auth.JwtTokenProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private val logger = KotlinLogging.logger {}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class GatewayJwtFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val redisTemplate: StringRedisTemplate
) : OncePerRequestFilter() {

    companion object {
        private const val BLACKLIST_PREFIX = "blacklist:"

        private val PUBLIC_PATHS = listOf(
            "/api/auth/login",
            "/api/auth/signup",
            "/api/auth/refresh",
            "/api/auth/password",
            "/api/auth/email",
            "/api/payments/webhook",
            "/actuator"
        )
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (isPublicPath(request.requestURI)) {
            filterChain.doFilter(request, response)
            return
        }

        val token = resolveToken(request)
        if (token == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing token")
            return
        }

        if (!jwtTokenProvider.validateToken(token)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token")
            return
        }

        if (jwtTokenProvider.getTokenType(token) != "access") {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Access token required")
            return
        }

        if (isBlacklisted(token)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token has been revoked")
            return
        }

        val userId = jwtTokenProvider.getUserId(token)
        val role = jwtTokenProvider.getRole(token)

        val mutatedRequest = MutableHttpServletRequest(request)
        mutatedRequest.addHeader("X-User-Id", userId.toString())
        mutatedRequest.addHeader("X-User-Role", role.name)

        logger.debug { "Gateway auth: userId=$userId, role=$role, path=${request.requestURI}" }

        filterChain.doFilter(mutatedRequest, response)
    }

    private fun isPublicPath(uri: String) = PUBLIC_PATHS.any { uri == it || uri.startsWith("$it/") }

    private fun isBlacklisted(token: String) = redisTemplate.hasKey("$BLACKLIST_PREFIX$token")

    private fun resolveToken(request: HttpServletRequest): String? {
        val bearer = request.getHeader("Authorization") ?: return null
        return if (bearer.startsWith("Bearer ")) bearer.substring(7) else null
    }
}
