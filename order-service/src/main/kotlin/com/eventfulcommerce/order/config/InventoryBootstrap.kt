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
        val productIds = listOf("PRODUCT-001", "PRODUCT-002", "PRODUCT-003")

        productIds.forEach { productId ->
            val stockKey = "{product:$productId}:stock"
            val holdCountKey = "{product:$productId}:holdCount"

            if (!template.hasKey(stockKey)) {
                template.opsForValue().set(stockKey, "100")
            }

            if (!template.hasKey(holdCountKey)) {
                template.opsForValue().set(holdCountKey, "0")
            }
        }
    }
}
