package com.eventfulcommerce.user.interceptor

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
        val ipAddress = getClientIP(request)
        
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
    
    /**
     * 클라이언트 IP 추출
     */
    private fun getClientIP(request: HttpServletRequest): String {
        val headers = listOf(
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_X_FORWARDED_FOR",
            "HTTP_X_FORWARDED",
            "HTTP_X_CLUSTER_CLIENT_IP",
            "HTTP_CLIENT_IP",
            "HTTP_FORWARDED_FOR",
            "HTTP_FORWARDED",
            "HTTP_VIA",
            "REMOTE_ADDR"
        )
        
        for (header in headers) {
            val ip = request.getHeader(header)
            if (!ip.isNullOrBlank() && ip != "unknown") {
                return ip.split(",")[0].trim()
            }
        }
        
        return request.remoteAddr ?: "unknown"
    }
}
