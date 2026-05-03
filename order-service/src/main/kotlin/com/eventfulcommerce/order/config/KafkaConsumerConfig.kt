package com.eventfulcommerce.order.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

@Configuration
class KafkaConsumerConfig {

    @Bean
    fun defaultErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
        // 1초 간격, 최대 3회 재시도 후 {topic}.DLT로 격리
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate)
        val backoff = FixedBackOff(1000L, 3L)
        return DefaultErrorHandler(recoverer, backoff)
    }
}
