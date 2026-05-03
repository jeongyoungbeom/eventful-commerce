package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.domain.entity.AuditLog
import com.eventfulcommerce.user.domain.repository.AuditLogRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
@Transactional
class AuditLogService(
    private val auditLogRepository: AuditLogRepository
) {
    
    /**
     * 감사 로그 기록
     */

    fun log(
        userId: UUID?,
        action: String,
        success: Boolean,
        failureReason: String? = null,
        request: HttpServletRequest? = null
    ) {
        val auditLog = AuditLog(
            userId = userId,
            action = action,
            ipAddress = request?.let { getClientIP(it) },
            userAgent = request?.getHeader("User-Agent"),
            success = success,
            failureReason = failureReason
        )
        
        auditLogRepository.save(auditLog)
        
        logger.info {
            "📝 Audit Log: userId=$userId, action=$action, success=$success" +
            if (failureReason != null) ", reason=$failureReason" else ""
        }
    }
    
    /**
     * 로그인 성공 로그
     */
    fun logLoginSuccess(userId: UUID, request: HttpServletRequest?) {
        log(userId, "LOGIN", success = true, request = request)
    }
    
    /**
     * 로그인 실패 로그
     */
    fun logLoginFailure(email: String, reason: String, request: HttpServletRequest?) {
        log(userId = null, action = "LOGIN", success = false, failureReason = "$email: $reason", request = request)
    }
    
    /**
     * 로그아웃 로그
     */
    fun logLogout(userId: UUID, request: HttpServletRequest?) {
        log(userId, "LOGOUT", success = true, request = request)
    }
    
    /**
     * 회원가입 로그
     */
    fun logSignup(userId: UUID, request: HttpServletRequest?) {
        log(userId, "SIGNUP", success = true, request = request)
    }
    
    /**
     * 비밀번호 변경 로그
     */
    fun logPasswordChange(userId: UUID, request: HttpServletRequest?) {
        log(userId, "PASSWORD_CHANGE", success = true, request = request)
    }
    
    /**
     * 클라이언트 IP 추출
     */
    private fun getClientIP(request: HttpServletRequest): String {
        // Proxy를 거친 경우 실제 IP 추출
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
                // 여러 IP가 있는 경우 첫 번째 IP 사용
                return ip.split(",")[0].trim()
            }
        }
        
        return request.remoteAddr ?: "unknown"
    }
}
