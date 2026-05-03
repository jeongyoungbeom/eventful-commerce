package com.eventfulcommerce.common.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

private val logger = KotlinLogging.logger {}

@Configuration
@ConfigurationProperties(prefix = "jwt")
data class JwtConfig(
    var secret: String = "",
    var accessTokenValidity: Long = 3600000,
    var refreshTokenValidity: Long = 604800000
) {
    @PostConstruct
    fun validate() {
        if (secret.isBlank()) {
            throw IllegalStateException(
                "JWT secret is not configured! Set 'jwt.secret' or JWT_SECRET environment variable. " +
                "Generate with: openssl rand -base64 64"
            )
        }
        if (secret.length < 32) {
            throw IllegalStateException(
                "JWT secret too weak (${secret.length} chars). Minimum 32, recommended 64+."
            )
        }
        if (secret.length < 64) {
            logger.warn { "JWT secret is ${secret.length} chars. Consider 64+ for better security." }
        }
        logger.info { "JWT Configuration validated (secret length: ${secret.length})" }
    }
}
