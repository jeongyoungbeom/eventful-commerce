package com.eventfulcommerce.inventory.config

import com.eventfulcommerce.common.auth.JwtTokenProvider
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity, jwt: JwtTokenProvider,
        @Value("\${inventory.service-token:}") serviceToken: String): SecurityFilterChain {
        http.csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> response.sendError(401) }
                    .accessDeniedHandler { _, response, _ -> response.status = 403 }
            }
            .authorizeHttpRequests {
                it.requestMatchers(org.springframework.http.HttpMethod.GET, "/inventory/admin/index.html", "/inventory/admin/app.js", "/inventory/admin/styles.css").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/prometheus", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    .requestMatchers("/inventory/**").hasRole("ADMIN")
                    .requestMatchers("/internal/reservations/**").hasAuthority("INVENTORY_SERVICE")
                    .anyRequest().denyAll()
            }
            .addFilterBefore(InventoryJwtFilter(jwt, serviceToken), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}

class InventoryJwtFilter(private val jwt: JwtTokenProvider, private val serviceToken: String) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (request.requestURI.removePrefix(request.contextPath).startsWith("/internal/")) {
            if (serviceToken.isBlank()) {
                response.sendError(403)
                return
            }
            val supplied = header?.takeIf { it.startsWith("Bearer ") }?.substring(7)
            if (supplied == null || !MessageDigest.isEqual(serviceToken.toByteArray(StandardCharsets.UTF_8),
                    supplied.toByteArray(StandardCharsets.UTF_8))) {
                response.sendError(401)
                return
            }
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken("order-service", null,
                listOf(SimpleGrantedAuthority("INVENTORY_SERVICE")))
            chain.doFilter(request, response)
            return
        }
        if (header != null) {
            val authentication = try {
                require(header.startsWith("Bearer "))
                val token = header.substring(7)
                require(jwt.getTokenType(token) == "access")
                require(jwt.getRemainingValidity(token) > 0)
                UsernamePasswordAuthenticationToken(jwt.getUserId(token), null,
                    listOf(SimpleGrantedAuthority("ROLE_${jwt.getRole(token).name}")))
            } catch (_: Exception) {
                response.sendError(401)
                return
            }
            SecurityContextHolder.getContext().authentication = authentication
        }
        chain.doFilter(request, response)
    }
}
