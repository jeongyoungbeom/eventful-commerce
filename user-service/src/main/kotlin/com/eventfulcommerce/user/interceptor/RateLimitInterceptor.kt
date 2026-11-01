package com.eventfulcommerce.user.interceptor

import com.eventfulcommerce.user.web.clientIp
import com.eventfulcommerce.user.service.RateLimitService
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Component
class RateLimitInterceptor(
    private val rateLimitService: RateLimitService,
    private val objectMapper: ObjectMapper
) : HandlerInterceptor {
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val ipAddress = clientIp(request)
        
        // Rate Limit 확인
        if (!rateLimitService.checkRateLimit(ipAddress)) {
            // Rate Limit 초과
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json"
            response.characterEncoding = "UTF-8"
            
            val errorResponse = mapOf(
                "status" to HttpStatus.TOO_MANY_REQUESTS.value(),
                "error" to "Too Many Requests",
                "message" to "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요.",
                "timestamp" to Instant.now().toString(),
                "remaining" to rateLimitService.getRemainingRequests(ipAddress),
                "resetTime" to rateLimitService.getResetTime(ipAddress)
            )
            
            response.writer.write(objectMapper.writeValueAsString(errorResponse))
            
            logger.warn { "🚫 Rate Limit 차단: IP=$ipAddress" }
            return false
        }
        
        return true
    }
    
}
