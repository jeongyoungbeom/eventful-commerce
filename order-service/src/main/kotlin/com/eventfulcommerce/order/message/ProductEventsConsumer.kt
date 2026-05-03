package com.eventfulcommerce.order.message

import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.ProductDeactivatedPayload
import com.eventfulcommerce.common.ProductRegisteredPayload
import com.eventfulcommerce.common.ProductStockUpdatedPayload
import com.eventfulcommerce.order.domain.entity.ProductReadModel
import com.eventfulcommerce.order.repository.ProductReadModelRepository
import com.eventfulcommerce.order.service.InventoryReservationService
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

@Component
class ProductEventsConsumer(
    private val productReadModelRepository: ProductReadModelRepository,
    private val inventoryReservationService: InventoryReservationService,
    private val objectMapper: ObjectMapper
) {
    @KafkaListener(topics = ["product-events"], groupId = "order-service-group")
    @Transactional
    fun consume(message: String) {
        val event = objectMapper.readValue(message, OutboxEventMessage::class.java)

        when (event.eventType) {
            "PRODUCT_REGISTERED" -> handleProductRegistered(event)
            "PRODUCT_STOCK_UPDATED" -> handleProductStockUpdated(event)
            "PRODUCT_DEACTIVATED" -> handleProductDeactivated(event)
            else -> logger.warn { "알 수 없는 product 이벤트 타입: ${event.eventType}" }
        }
    }

    private fun handleProductRegistered(event: OutboxEventMessage) {
        val payload = objectMapper.readValue(event.payload, ProductRegisteredPayload::class.java)

        if (productReadModelRepository.existsById(payload.productId)) {
            logger.info { "이미 처리된 상품 등록 이벤트: ${payload.productId}" }
            return
        }

        productReadModelRepository.save(ProductReadModel(
            productId = payload.productId,
            sellerId = payload.sellerId,
            name = payload.name,
            price = payload.price,
            stock = payload.initialStock,
            category = payload.category
        ))

        inventoryReservationService.initializeStock(payload.productId.toString(), payload.initialStock)

        logger.info { "상품 읽기 모델 생성 + Redis 재고 초기화: ${payload.productId}, stock=${payload.initialStock}" }
    }

    private fun handleProductStockUpdated(event: OutboxEventMessage) {
        val payload = objectMapper.readValue(event.payload, ProductStockUpdatedPayload::class.java)

        val readModel = productReadModelRepository.findByIdOrNull(payload.productId) ?: run {
            logger.warn { "읽기 모델 없음 (재고 변경 무시): ${payload.productId}" }
            return
        }

        readModel.updateStock(payload.stockDelta, payload.newStock)
        inventoryReservationService.adjustStock(payload.productId.toString(), payload.stockDelta)

        logger.info { "재고 변경 반영: ${payload.productId}, delta=${payload.stockDelta}, newStock=${payload.newStock}" }
    }

    private fun handleProductDeactivated(event: OutboxEventMessage) {
        val payload = objectMapper.readValue(event.payload, ProductDeactivatedPayload::class.java)

        val readModel = productReadModelRepository.findByIdOrNull(payload.productId) ?: run {
            logger.warn { "읽기 모델 없음 (비활성화 무시): ${payload.productId}" }
            return
        }

        readModel.deactivate()
        logger.info { "상품 비활성화 반영: ${payload.productId}" }
    }
}
