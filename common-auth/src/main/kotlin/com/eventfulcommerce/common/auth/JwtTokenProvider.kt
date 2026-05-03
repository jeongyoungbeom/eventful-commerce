package com.eventfulcommerce.common.auth

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

    fun createAccessToken(userId: UUID, role: UserRole): String {
        val now = Date()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("role", role.toString())
            .claim("type", "access")
            .issuedAt(now)
            .expiration(Date(now.time + jwtConfig.accessTokenValidity))
            .signWith(key)
            .compact()
    }

    fun createRefreshToken(userId: UUID, role: UserRole): String {
        val now = Date()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("role", role.toString())
            .claim("type", "refresh")
            .issuedAt(now)
            .expiration(Date(now.time + jwtConfig.refreshTokenValidity))
            .signWith(key)
            .compact()
    }

    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token)
            true
        } catch (e: Exception) {
            logger.warn { "Token validation failed: ${e.message}" }
            false
        }
    }

    fun getUserId(token: String): UUID = UUID.fromString(getClaims(token).subject)

    fun getRole(token: String): UserRole {
        val role = getClaims(token)["role"] as? String
            ?: throw IllegalArgumentException("No role in token")
        return UserRole.valueOf(role)
    }

    fun getTokenType(token: String): String = getClaims(token)["type"] as? String ?: "unknown"

    fun getExpiration(token: String): Date = getClaims(token).expiration

    fun getRemainingValidity(token: String): Long = getExpiration(token).time - System.currentTimeMillis()

    private fun getClaims(token: String): Claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
}
