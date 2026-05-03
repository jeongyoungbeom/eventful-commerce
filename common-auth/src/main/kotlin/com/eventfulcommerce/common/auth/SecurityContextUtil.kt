package com.eventfulcommerce.common.auth

import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

object SecurityContextUtil {

    fun getCurrentUserId(): UUID {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw IllegalStateException("인증 정보가 없습니다")
        return authentication.principal as? UUID
            ?: throw IllegalStateException("인증 principal이 UUID 타입이 아닙니다")
    }

    fun getCurrentUserRole(): String? {
        return SecurityContextHolder.getContext().authentication
            ?.authorities
            ?.firstOrNull()
            ?.authority
            ?.removePrefix("ROLE_")
    }
}
