package com.eventfulcommerce.user.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "jwt")
data class JwtConfig(
    var secret: String = "",
    var accessTokenValidity: Long = 3600000,  // 1시간 (ms)
    var refreshTokenValidity: Long = 604800000  // 7일 (ms)
)
