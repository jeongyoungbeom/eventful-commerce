package com.eventfulcommerce.order.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@Configuration
@EnableJpaAuditing
class JpaAuditingConfig {
    @org.springframework.context.annotation.Bean
    fun orderClock(): java.time.Clock = java.time.Clock.systemUTC()
}