package com.eventfulcommerce.gateway.filter

import com.eventfulcommerce.common.auth.JwtTokenProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
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
            "/actuator",
            "/swagger-ui",
            "/swagger-ui.html",
            "/v3/api-docs",
            "/api-docs"
        )
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // CORS preflight는 JWT 없이 통과
        if (request.method == "OPTIONS") {
            filterChain.doFilter(request, response)
            return
        }

        if (isPublicPath(request)) {
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

        // Spring Security 컨텍스트에 인증 정보 등록 → SecurityConfig의 authenticated() 체크가 동작함
        val authentication = UsernamePasswordAuthenticationToken(
            userId.toString(),
            null,
            listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
        )
        SecurityContextHolder.getContext().authentication = authentication

        val mutatedRequest = MutableHttpServletRequest(request)
        mutatedRequest.addHeader("X-User-Id", userId.toString())
        mutatedRequest.addHeader("X-User-Role", role.name)

        logger.debug { "Gateway auth: userId=$userId, role=$role, path=${request.requestURI}" }

        filterChain.doFilter(mutatedRequest, response)
    }

    private fun isPublicPath(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        if (PUBLIC_PATHS.any { uri == it || uri.startsWith("$it/") }) return true
        // 상품 목록/상세 조회는 비로그인 허용 (GET 전용, UUID 형식만 매칭하여 /my 등 특수 경로 제외)
        val uuidPattern = Regex("/api/products/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        if (request.method == "GET" && (uri == "/api/products" || uri.matches(uuidPattern))) return true
        return false
    }

    private fun isBlacklisted(token: String) = redisTemplate.hasKey("$BLACKLIST_PREFIX$token")

    private fun resolveToken(request: HttpServletRequest): String? {
        val bearer = request.getHeader("Authorization") ?: return null
        return if (bearer.startsWith("Bearer ")) bearer.substring(7) else null
    }
}
