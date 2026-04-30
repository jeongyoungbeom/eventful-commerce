package com.eventfulcommerce.user.config

import com.eventfulcommerce.user.filter.JwtAuthenticationFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val corsConfigurationSource: CorsConfigurationSource
) {
    
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // CSRF 비활성화 (JWT 사용으로 불필요)
            .csrf { it.disable() }
            
            // CORS 설정
            .cors { it.configurationSource(corsConfigurationSource) }
            
            // 세션 사용 안 함 (Stateless)
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            
            // 요청별 인증 설정
            .authorizeHttpRequests { auth ->
                auth
                    // 공개 API (인증 불필요)
                    .requestMatchers(
                        "/auth/signup/**",
                        "/auth/login",
                        "/auth/refresh",
                        "/auth/password/**",      // 비밀번호 재설정
                        "/auth/email/**",         // 이메일 인증
                        "/actuator/**",
                        "/error"
                    ).permitAll()
                    
                    // 그 외 모든 요청은 인증 필요
                    .anyRequest().authenticated()
            }
            
            // JWT 인증 필터 추가
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter::class.java
            )
            
            // 보안 헤더 설정
            .headers { headers ->
                headers
                    // Clickjacking 방지
                    .frameOptions { it.deny() }
                    
                    // XSS 방지
                    .xssProtection { it.headerValue(
                        org.springframework.security.web.header.writers.XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK
                    ) }
                    
                    // Content Security Policy
                    .contentSecurityPolicy { it.policyDirectives("default-src 'self'") }
                    
                    // HSTS (HTTPS 강제)
                    .httpStrictTransportSecurity {
                        it.maxAgeInSeconds(31536000)  // 1년
                          .includeSubDomains(true)
                    }
            }
        
        return http.build()
    }
}
