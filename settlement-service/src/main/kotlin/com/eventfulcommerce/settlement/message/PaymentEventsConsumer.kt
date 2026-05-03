package com.eventfulcommerce.settlement.message

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.settlement.service.SettlementService
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
class PaymentEventsConsumer(
    private val settlementService: SettlementService,
    private val idempotencyHandler: IdempotencyHandler,
    private val objectMapper: ObjectMapper
) {
    @KafkaListener(topics = ["payment-events"], groupId = "settlement-service-group")
    fun consume(message: String) {
        val event = objectMapper.readTree(message)
        val eventType = event["eventType"]?.asText() ?: return

        if (eventType != "PAYMENT_COMPLETED") return

        val eventId = UUID.fromString(event["eventId"].asText())

        idempotencyHandler.executeIdempotent(eventId) {
            val payload = objectMapper.readTree(event["payload"].asText())

            val paymentId = UUID.fromString(payload["paymentId"].asText())
            val orderId = UUID.fromString(payload["orderId"].asText())
            val sellerId = UUID.fromString(payload["sellerId"].asText())
            val userId = UUID.fromString(payload["userId"].asText())
            val amount = payload["amount"].asLong()

            settlementService.createSettlement(
                paymentId = paymentId,
                orderId = orderId,
                sellerId = sellerId,
                userId = userId,
                totalAmount = amount
            )

            logger.info { "정산 생성 완료: paymentId=$paymentId" }
        }
    }
}
