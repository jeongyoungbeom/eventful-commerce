package com.eventfulcommerce.product.message

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.InventoryStockAdjustedPayload
import com.eventfulcommerce.common.InventoryStockSnapshotPayload
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.product.service.ProductService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class InventoryEventsConsumer(
    private val objectMapper: ObjectMapper,
    private val idempotencyHandler: IdempotencyHandler,
    private val productService: ProductService
) {
    @KafkaListener(topics = ["order-events"], groupId = "product-service")
    @Transactional
    fun consume(message: String) {
        val event = objectMapper.readValue(message, OutboxEventMessage::class.java)
        if (event.eventType !in SUPPORTED_EVENT_TYPES) return

        idempotencyHandler.executeIdempotent(event.eventId) {
            when (event.eventType) {
                INVENTORY_STOCK_ADJUSTED -> productService.applyInventoryStockAdjustment(
                    objectMapper.readValue(event.payload, InventoryStockAdjustedPayload::class.java),
                    event.occurredAt
                )
                INVENTORY_STOCK_SNAPSHOT -> productService.applyInventoryStockSnapshot(
                    objectMapper.readValue(event.payload, InventoryStockSnapshotPayload::class.java),
                    event.occurredAt
                )
            }
        }
    }

    private companion object {
        const val INVENTORY_STOCK_ADJUSTED = "INVENTORY_STOCK_ADJUSTED"
        const val INVENTORY_STOCK_SNAPSHOT = "INVENTORY_STOCK_SNAPSHOT"
        val SUPPORTED_EVENT_TYPES = setOf(INVENTORY_STOCK_ADJUSTED, INVENTORY_STOCK_SNAPSHOT)
    }
}
