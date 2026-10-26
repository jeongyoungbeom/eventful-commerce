package com.eventfulcommerce.gateway.config

import com.eventfulcommerce.common.auth.JwtConfig
import com.eventfulcommerce.common.auth.JwtTokenProvider
import com.eventfulcommerce.common.auth.UserRole
import com.eventfulcommerce.gateway.filter.GatewayJwtFilter
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.Filter
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.*
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

class PaymentWebhookSecurityTest {
    @Test
    fun `정확한 POST 실제 웹훅만 JWT 예외이며 시뮬레이션에는 access JWT가 필요하다`() {
        WebApplicationContextRunner().withUserConfiguration(PaymentGatewaySecurityConfiguration::class.java).run { context ->
            SecurityContextHolder.clearContext()
            val mvc = MockMvcBuilders.webAppContextSetup(context.sourceApplicationContext)
                .addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(context.getBean("springSecurityFilterChain", Filter::class.java)).build()
            mvc.perform(post("/api/payments/webhook")).andExpect(status().isOk)
            listOf("/api/payments/webhook/child", "/api/payments/simulation/webhook").forEach {
                mvc.perform(post(it)).andExpect(status().isUnauthorized)
            }
            mvc.perform(get("/api/payments/webhook")).andExpect(status().isUnauthorized)
            mvc.perform(post("/api/payments/simulation/webhook")
                .header("X-Payment-Simulation-Token", "local-token")).andExpect(status().isUnauthorized)
            val jwt = context.getBean(JwtTokenProvider::class.java)
            mvc.perform(post("/api/payments/simulation/webhook").header(HttpHeaders.AUTHORIZATION,
                "Bearer ${jwt.createRefreshToken(UUID.randomUUID(), UserRole.USER)}")).andExpect(status().isUnauthorized)
            mvc.perform(post("/api/payments/simulation/webhook").header(HttpHeaders.AUTHORIZATION,
                "Bearer ${jwt.createAccessToken(UUID.randomUUID(), UserRole.USER)}")
                .header("X-Payment-Simulation-Token", "local-token"))
                .andExpect(status().isOk).andExpect(content().string("local-token"))
            SecurityContextHolder.clearContext()
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableWebMvc
@Import(SecurityConfig::class, GatewayJwtFilter::class, PaymentGatewayProbeController::class)
class PaymentGatewaySecurityConfiguration {
    @Bean fun jwt(): JwtTokenProvider = JwtTokenProvider(JwtConfig(secret = "01234567890123456789012345678901"))
    @Bean fun redis(): StringRedisTemplate = mockk<StringRedisTemplate>(relaxed = true).also {
        every { it.hasKey(any()) } returns false
    }
    @Bean fun corsConfigurationSource(): CorsConfigurationSource = UrlBasedCorsConfigurationSource().apply {
        registerCorsConfiguration("/**", CorsConfiguration())
    }
}

@RestController
class PaymentGatewayProbeController {
    @PostMapping("/api/payments/webhook") fun webhook() = "PG route"
    @PostMapping("/api/payments/simulation/webhook")
    fun simulation(@RequestHeader("X-Payment-Simulation-Token") token: String) = token
}
