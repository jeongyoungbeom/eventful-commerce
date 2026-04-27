package com.eventfulcommerce.notification.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "telegram.bot")
data class TelegramProperties(
    val token: String,
    val username: String
)
