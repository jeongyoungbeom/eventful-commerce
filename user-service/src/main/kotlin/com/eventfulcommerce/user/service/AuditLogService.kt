package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.web.clientIp
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
            ipAddress = request?.let { clientIp(it) },
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
    
}
