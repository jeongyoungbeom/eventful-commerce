package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.config.JwtConfig
import com.eventfulcommerce.user.domain.entity.UserRole
import io.github.oshai.kotlinlogging.KotlinLogging
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.util.*
import javax.crypto.SecretKey

private val logger = KotlinLogging.logger {}

@Component
class JwtTokenProvider(
    private val jwtConfig: JwtConfig
) {
    
    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(jwtConfig.secret.toByteArray())
    }
    
    /**
     * Access Token 생성
     */
    fun createAccessToken(userId: UUID, role: UserRole): String {
        val now = Date()
        val validity = Date(now.time + jwtConfig.accessTokenValidity)
        
        val token = Jwts.builder()
            .subject(userId.toString())
            .claim("role", role.toString())
            .claim("type", "access")
            .issuedAt(now)
            .expiration(validity)
            .signWith(key)
            .compact()
        
        logger.debug { "Access Token 생성: userId=$userId, role=$role" }
        return token
    }
    
    /**
     * Refresh Token 생성
     */
    fun createRefreshToken(userId: UUID): String {
        val now = Date()
        val validity = Date(now.time + jwtConfig.refreshTokenValidity)
        
        val token = Jwts.builder()
            .subject(userId.toString())
            .claim("type", "refresh")
            .issuedAt(now)
            .expiration(validity)
            .signWith(key)
            .compact()
        
        logger.debug { "Refresh Token 생성: userId=$userId" }
        return token
    }
    
    /**
     * 토큰 검증
     */
    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
            true
        } catch (e: Exception) {
            logger.warn { "토큰 검증 실패: ${e.message}" }
            false
        }
    }
    
    /**
     * 토큰에서 Claims 추출
     */
    private fun getClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
    }
    
    /**
     * 토큰에서 사용자 ID 추출
     */
    fun getUserId(token: String): UUID {
        val claims = getClaims(token)
        return UUID.fromString(claims.subject as String?)
    }
    
    /**
     * 토큰에서 권한 추출
     */
    fun getRole(token: String): UserRole {
        val claims = getClaims(token)
        val role = claims["role"] as? String
            ?: throw IllegalArgumentException("토큰에 role 정보가 없습니다")
        return UserRole.valueOf(role)
    }
    
    /**
     * 토큰 타입 확인 (access/refresh)
     */
    fun getTokenType(token: String): String {
        val claims = getClaims(token)
        return claims["type"] as? String ?: "unknown"
    }
    
    /**
     * 토큰 만료 시간 추출
     */
    fun getExpiration(token: String): Date {
        val claims = getClaims(token)
        return claims.expiration
    }
    
    /**
     * 토큰 남은 유효 시간 (ms)
     */
    fun getRemainingValidity(token: String): Long {
        val expiration = getExpiration(token)
        return expiration.time - System.currentTimeMillis()
    }
}
