package com.eventfulcommerce.common

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "dlt", name = ["topics"])
class DeadLetterEventConsumer(
    private val deadLetterEventService: DeadLetterEventService
) {
    @KafkaListener(
        topics = ["#{'\${dlt.topics}'.split(',')}"],
        containerFactory = "dltArchiveKafkaListenerContainerFactory",
        groupId = "\${spring.application.name}-dlt-archive"
    )
    fun archive(record: ConsumerRecord<String, String>) {
        deadLetterEventService.archive(record)
    }
}
