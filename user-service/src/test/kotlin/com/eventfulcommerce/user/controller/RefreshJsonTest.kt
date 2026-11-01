package com.eventfulcommerce.user.controller

import com.eventfulcommerce.user.dto.RefreshRequest
import com.eventfulcommerce.user.service.AuthService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class RefreshJsonTest {
    @Test fun `실제 refresh JSON 객체는 서비스에 원본 토큰으로 전달된다`() {
        val service=mock(AuthService::class.java)
        val mvc=MockMvcBuilders.standaloneSetup(AuthController(service)).setControllerAdvice(com.eventfulcommerce.user.exception.GlobalExceptionHandler()).build()
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""{"refreshToken":"test-only-token"}"""))
            .andExpect(status().isOk)
        verify(service).refreshAccessToken(RefreshRequest("test-only-token"))
    }
    @Test fun `필수 refresh 누락과 빈값은 400이다`() {
        val service=mock(AuthService::class.java)
        val mvc=MockMvcBuilders.standaloneSetup(AuthController(service)).setControllerAdvice(com.eventfulcommerce.user.exception.GlobalExceptionHandler()).build()
        for (body in listOf("{}", """{"refreshToken":null}""", """{"refreshToken":""}""")) {
            mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest)
        }
        verifyNoInteractions(service)
    }
}
