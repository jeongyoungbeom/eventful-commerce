package com.eventfulcommerce.user.config

import com.eventfulcommerce.common.auth.GatewayAuthFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/auth/signup/**",
                        "/auth/login/**",
                        "/auth/refresh",
                        "/auth/password/**",
                        "/actuator/**",
                        "/error"
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(GatewayAuthFilter(), UsernamePasswordAuthenticationFilter::class.java)
            .headers { headers ->
                headers
                    .frameOptions { it.deny() }
                    .xssProtection { it.headerValue(
                        org.springframework.security.web.header.writers.XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK
                    ) }
                    .contentSecurityPolicy { it.policyDirectives("default-src 'self'") }
                    .httpStrictTransportSecurity {
                        it.maxAgeInSeconds(31536000)
                          .includeSubDomains(true)
                    }
            }

        return http.build()
    }
}
