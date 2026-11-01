package com.eventfulcommerce.inventory.message

import com.eventfulcommerce.common.*
import com.eventfulcommerce.inventory.repository.InventorySkuRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ProductEventsConsumer(private val skus: InventorySkuRepository, private val idempotency: IdempotencyHandler,
    private val mapper: ObjectMapper) {
    @KafkaListener(topics = ["product-events"], groupId = "inventory-service-group")
    @Transactional(rollbackFor = [Exception::class])
    fun consume(message: String) {
        val event = mapper.readValue(message, OutboxEventMessage::class.java)
        if (event.eventType !in setOf("PRODUCT_REGISTERED", "PRODUCT_UPDATED", "PRODUCT_DEACTIVATED")) return
        require(event.aggregateType == "PRODUCT") { "상품 aggregate가 아닙니다" }
        idempotency.executeIdempotent(event.eventId) {
            when (event.eventType) {
                "PRODUCT_REGISTERED" -> {
                    val p = mapper.readValue(event.payload, ProductRegisteredPayload::class.java)
                    require(p.productId == event.aggregateId)
                    if (p.salesType == SalesType.DIRECT_PURCHASE) {
                        require(p.name.isNotBlank() && p.name.length <= 255 && p.initialStock == 0)
                        skus.insertIfAbsent(p.skuId, p.name)
                    }
                }
                "PRODUCT_UPDATED" -> {
                    val p = mapper.readValue(event.payload, ProductUpdatedPayload::class.java)
                    require(p.productId == event.aggregateId)
                    if (p.salesType == SalesType.DIRECT_PURCHASE) {
                        require(p.name.isNotBlank() && p.name.length <= 255 && p.informationVersion > 0)
                        val sku = skus.lock(p.skuId) ?: error("직매입 상품 등록이 먼저 필요합니다")
                        if (p.informationVersion > sku.informationVersion) {
                            sku.name = p.name
                            sku.informationVersion = p.informationVersion
                        }
                    }
                }
                "PRODUCT_DEACTIVATED" -> {
                    val p = mapper.readValue(event.payload, ProductDeactivatedPayload::class.java)
                    require(p.productId == event.aggregateId)
                    if (p.sellerId == SalesType.OFFICIAL_SELLER_ID) {
                        val sku = skus.lock(p.productId) ?: error("직매입 상품 등록이 먼저 필요합니다")
                        sku.active = false
                    }
                }
            }
        }
    }
}
