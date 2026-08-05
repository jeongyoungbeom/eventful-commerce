package com.eventfulcommerce.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

@Configuration
class DeadLetterKafkaConfig {
    /**
     * A DLT archive failure must never create a second-level DLT such as
     * `payment-events.DLT.DLT`. Keeping the same record on its first DLT is
     * safer: the partition pauses and resumes automatically once its DB issue is fixed.
     */
    @Bean
    fun dltArchiveKafkaListenerContainerFactory(
        consumerFactory: ConsumerFactory<String, String>
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            this.consumerFactory = consumerFactory
            setCommonErrorHandler(
                DefaultErrorHandler(
                    FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS)
                )
            )
        }
}
