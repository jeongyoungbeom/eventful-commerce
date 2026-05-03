package com.eventfulcommerce.product.service

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.ProductDeactivatedPayload
import com.eventfulcommerce.common.ProductRegisteredPayload
import com.eventfulcommerce.common.ProductStockUpdatedPayload
import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductStatus
import com.eventfulcommerce.product.domain.entity.Product
import com.eventfulcommerce.product.dto.CreateProductRequest
import com.eventfulcommerce.product.dto.ProductResponse
import com.eventfulcommerce.product.dto.UpdateProductRequest
import com.eventfulcommerce.product.exception.ProductNotFoundException
import com.eventfulcommerce.product.exception.ProductOwnershipException
import com.eventfulcommerce.product.repository.ProductRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class ProductService(
    private val productRepository: ProductRepository,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun createProduct(request: CreateProductRequest, sellerId: UUID): ProductResponse {
        val product = Product(
            sellerId = sellerId,
            name = request.name,
            description = request.description,
            price = request.price,
            stock = request.stock,
            category = request.category
        )
        val saved = productRepository.save(product)

        val payload = ProductRegisteredPayload(
            productId = saved.id,
            sellerId = sellerId,
            name = saved.name,
            price = saved.price,
            initialStock = saved.stock,
            category = saved.category.name
        )

        outboxEventService.record(listOf(OutboxEvent(
            aggregateType = "PRODUCT",
            aggregateId = saved.id,
            eventType = "PRODUCT_REGISTERED",
            payload = objectMapper.writeValueAsString(payload),
            status = OutboxStatus.PENDING
        )))

        logger.info { "상품 등록: productId=${saved.id}, sellerId=$sellerId" }
        return ProductResponse.from(saved)
    }

    @Transactional
    fun updateProduct(productId: UUID, request: UpdateProductRequest, sellerId: UUID): ProductResponse {
        val product = getOwnedProduct(productId, sellerId)
        product.update(request.name, request.description, request.price, request.category)
        return ProductResponse.from(product)
    }

    @Transactional
    fun updateStock(productId: UUID, delta: Int, sellerId: UUID): ProductResponse {
        val product = getOwnedProduct(productId, sellerId)
        product.adjustStock(delta)

        val payload = ProductStockUpdatedPayload(
            productId = product.id,
            sellerId = sellerId,
            stockDelta = delta,
            newStock = product.stock
        )

        outboxEventService.record(listOf(OutboxEvent(
            aggregateType = "PRODUCT",
            aggregateId = product.id,
            eventType = "PRODUCT_STOCK_UPDATED",
            payload = objectMapper.writeValueAsString(payload),
            status = OutboxStatus.PENDING
        )))

        logger.info { "재고 변경: productId=$productId, delta=$delta, newStock=${product.stock}" }
        return ProductResponse.from(product)
    }

    @Transactional
    fun deactivateProduct(productId: UUID, sellerId: UUID) {
        val product = getOwnedProduct(productId, sellerId)
        product.deactivate()

        outboxEventService.record(listOf(OutboxEvent(
            aggregateType = "PRODUCT",
            aggregateId = product.id,
            eventType = "PRODUCT_DEACTIVATED",
            payload = objectMapper.writeValueAsString(ProductDeactivatedPayload(product.id, sellerId)),
            status = OutboxStatus.PENDING
        )))

        logger.info { "상품 비활성화: productId=$productId, sellerId=$sellerId" }
    }

    @Transactional(readOnly = true)
    fun getProducts(category: ProductCategory?): List<ProductResponse> {
        val products = if (category != null) {
            productRepository.findByStatusAndCategory(ProductStatus.ACTIVE, category)
        } else {
            productRepository.findByStatus(ProductStatus.ACTIVE)
        }
        return products.map { ProductResponse.from(it) }
    }

    @Transactional(readOnly = true)
    fun getProduct(productId: UUID): ProductResponse {
        val product = productRepository.findById(productId)
            .orElseThrow { ProductNotFoundException(productId) }
        return ProductResponse.from(product)
    }

    @Transactional(readOnly = true)
    fun getMyProducts(sellerId: UUID): List<ProductResponse> =
        productRepository.findBySellerIdAndStatus(sellerId, ProductStatus.ACTIVE)
            .map { ProductResponse.from(it) }

    private fun getOwnedProduct(productId: UUID, sellerId: UUID): Product {
        val product = productRepository.findById(productId)
            .orElseThrow { ProductNotFoundException(productId) }
        if (product.sellerId != sellerId) throw ProductOwnershipException(productId)
        return product
    }
}
