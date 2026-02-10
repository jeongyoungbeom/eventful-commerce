package com.eventfulcommerce.order.config

import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate

@Configuration
class InventoryBootstrap {

    @Bean
    @Profile("dev")
    fun initStock(template: StringRedisTemplate) = ApplicationRunner {
        val key = "stock:default"
        if (template.hasKey(key) != true) {
            template.opsForValue().set(key, "100")
        }
    }
}