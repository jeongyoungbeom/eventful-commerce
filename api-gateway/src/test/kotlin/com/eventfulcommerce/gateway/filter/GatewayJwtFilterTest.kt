package com.eventfulcommerce.gateway.filter

import com.eventfulcommerce.common.auth.JwtConfig
import com.eventfulcommerce.common.auth.JwtTokenProvider
import com.eventfulcommerce.common.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class GatewayJwtFilterTest {
    private lateinit var jwtTokenProvider: JwtTokenProvider
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var filter: GatewayJwtFilter

    @BeforeEach
    fun setUp() {
        jwtTokenProvider = JwtTokenProvider(
            JwtConfig(
                secret = "01234567890123456789012345678901",
                accessTokenValidity = 60_000,
                refreshTokenValidity = 120_000
            )
        )
        redisTemplate = mockk()
        filter = GatewayJwtFilter(jwtTokenProvider, redisTemplate)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `public path는 토큰 없이 통과한다`() {
        val request = MockHttpServletRequest("POST", "/api/auth/login")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `보호 API는 토큰이 없으면 401을 반환한다`() {
        val request = MockHttpServletRequest("GET", "/api/orders")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(401, response.status)
    }

    @Test
    fun `refresh token으로 보호 API에 접근하면 401을 반환한다`() {
        val userId = UUID.randomUUID()
        val refreshToken = jwtTokenProvider.createRefreshToken(userId, UserRole.USER)
        val request = MockHttpServletRequest("GET", "/api/orders").apply {
            addHeader("Authorization", "Bearer $refreshToken")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(401, response.status)
    }

    @Test
    fun `blacklist token은 401을 반환한다`() {
        val userId = UUID.randomUUID()
        val accessToken = jwtTokenProvider.createAccessToken(userId, UserRole.USER)
        every { redisTemplate.hasKey("blacklist:$accessToken") } returns true
        val request = MockHttpServletRequest("GET", "/api/orders").apply {
            addHeader("Authorization", "Bearer $accessToken")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(401, response.status)
    }

    @Test
    fun `정상 access token은 인증 컨텍스트와 사용자 헤더를 주입한다`() {
        val userId = UUID.randomUUID()
        val accessToken = jwtTokenProvider.createAccessToken(userId, UserRole.SELLER)
        every { redisTemplate.hasKey("blacklist:$accessToken") } returns false
        val request = MockHttpServletRequest("GET", "/api/orders").apply {
            addHeader("Authorization", "Bearer $accessToken")
        }
        val response = MockHttpServletResponse()
        val chain = CapturingFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertEquals(userId.toString(), SecurityContextHolder.getContext().authentication.name)
        assertTrue(SecurityContextHolder.getContext().authentication.authorities.any { it.authority == "ROLE_SELLER" })
        assertEquals(userId.toString(), chain.capturedRequest?.getHeader("X-User-Id"))
        assertEquals("SELLER", chain.capturedRequest?.getHeader("X-User-Role"))
    }

    private class CapturingFilterChain : MockFilterChain() {
        var capturedRequest: jakarta.servlet.http.HttpServletRequest? = null

        override fun doFilter(request: jakarta.servlet.ServletRequest, response: jakarta.servlet.ServletResponse) {
            capturedRequest = request as jakarta.servlet.http.HttpServletRequest
            super.doFilter(request, response)
        }
    }
}
