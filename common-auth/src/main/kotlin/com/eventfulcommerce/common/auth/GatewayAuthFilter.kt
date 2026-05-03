package com.eventfulcommerce.common.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

class GatewayAuthFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val userId = request.getHeader("X-User-Id")
        val role = request.getHeader("X-User-Role")

        if (userId != null && role != null) {
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                UUID.fromString(userId),
                null,
                listOf(SimpleGrantedAuthority("ROLE_$role"))
            )
        }

        filterChain.doFilter(request, response)
    }
}
