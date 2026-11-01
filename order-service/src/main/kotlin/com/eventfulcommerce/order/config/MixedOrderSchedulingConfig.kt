package com.eventfulcommerce.order.config

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MixedOrderSchedulingConfig {
    // 전용 스케줄러가 Boot 기본 빈 생성을 막으므로 기존 정기 작업의 기본 빈도 유지한다.
    @Bean
    fun taskScheduler(builder: ThreadPoolTaskSchedulerBuilder) = builder.build()

    @Bean
    fun mixedOrderTaskScheduler(builder: ThreadPoolTaskSchedulerBuilder) =
        builder.poolSize(1).threadNamePrefix("mixed-order-recovery-").build()
}
