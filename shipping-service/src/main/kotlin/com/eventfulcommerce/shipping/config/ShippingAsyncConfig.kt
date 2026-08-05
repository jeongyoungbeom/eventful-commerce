package com.eventfulcommerce.shipping.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
class ShippingAsyncConfig {
    @Bean("shippingCompletionExecutor")
    fun shippingCompletionExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 8
        setQueueCapacity(500)
        setThreadNamePrefix("shipping-completion-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }
}
