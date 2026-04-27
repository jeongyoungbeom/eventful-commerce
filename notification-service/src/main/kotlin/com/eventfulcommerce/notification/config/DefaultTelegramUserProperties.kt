package com.eventfulcommerce.notification.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "telegram.default-user")
data class DefaultTelegramUserProperties(
    val userId: String = "",
    val chatId: String = ""
)
