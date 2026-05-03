package com.eventfulcommerce.payment.config

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
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate)
        val backoff = FixedBackOff(1000L, 3L)
        return DefaultErrorHandler(recoverer, backoff)
    }
}
