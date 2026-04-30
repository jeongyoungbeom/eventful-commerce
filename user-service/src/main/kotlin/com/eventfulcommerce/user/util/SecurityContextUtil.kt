package com.eventfulcommerce.user.util

import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

object SecurityContextUtil {
    
    /**
     * 현재 인증된 사용자의 ID 조회
     */
    fun getCurrentUserId(): UUID? {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: return null
        
        return try {
            authentication.principal as? UUID
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 현재 인증된 사용자의 권한 조회
     */
    fun getCurrentUserRole(): String? {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: return null
        
        return authentication.authorities
            .firstOrNull()
            ?.authority
            ?.removePrefix("ROLE_")
    }
    
    /**
     * 현재 사용자가 인증되었는지 확인
     */
    fun isAuthenticated(): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication
        return authentication != null && authentication.isAuthenticated
    }
    
    /**
     * 현재 사용자가 특정 권한을 가지고 있는지 확인
     */
    fun hasRole(role: String): Boolean {
        val currentRole = getCurrentUserRole()
        return currentRole == role
    }
}
