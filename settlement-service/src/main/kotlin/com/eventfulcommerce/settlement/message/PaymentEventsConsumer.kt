package com.eventfulcommerce.settlement.message

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.PaymentRefundedPayload
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

        val eventId = UUID.fromString(event["eventId"].asText())

        idempotencyHandler.executeIdempotent(eventId) {
            when (eventType) {
                "PAYMENT_COMPLETED" -> {
                    val payload = objectMapper.readValue(event["payload"].asText(), PaymentCompletedPayload::class.java)
                    payload.sellerOrders.forEach { sellerOrder ->
                        settlementService.createSettlement(
                            paymentId = payload.paymentId,
                            orderId = payload.orderId,
                            sellerOrderId = sellerOrder.sellerOrderId,
                            sellerId = sellerOrder.sellerId,
                            userId = payload.userId,
                            totalAmount = sellerOrder.paymentAmount,
                            platformFee = sellerOrder.commissionAmount,
                            sellerAmount = sellerOrder.settlementAmount
                        )
                    }
                    logger.info { "정산 생성 완료: paymentId=${payload.paymentId}, sellerOrders=${payload.sellerOrders.size}" }
                }
                "PAYMENT_REFUNDED" -> {
                    val payload = objectMapper.readValue(event["payload"].asText(), PaymentRefundedPayload::class.java)
                    settlementService.applyRefund(payload.sellerOrderId, payload.amount)
                    logger.info { "정산 환불 차감 완료: refundId=${payload.refundId}" }
                }
            }
        }
    }
}
