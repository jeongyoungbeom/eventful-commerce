package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.shipping.repository.ShippingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ShippingService(
    private val processedEventRepository: ProcessedEventRepository,
    private val shippingRepository: ShippingRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun handlePaymentCompleted(eventId: UUID, payloadJson: String) {

    }
}