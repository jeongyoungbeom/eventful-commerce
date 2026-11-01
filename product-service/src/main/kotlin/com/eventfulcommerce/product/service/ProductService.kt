package com.eventfulcommerce.product.service

import com.eventfulcommerce.common.SalesType
import com.eventfulcommerce.product.domain.entity.OfficialSeller
import com.eventfulcommerce.product.repository.OfficialSellerRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.access.AccessDeniedException
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.InventoryStockAdjustedPayload
import com.eventfulcommerce.common.InventoryStockSnapshotPayload
import com.eventfulcommerce.common.ProductDeactivatedPayload
import com.eventfulcommerce.common.ProductRegisteredPayload
import com.eventfulcommerce.common.ProductUpdatedPayload
import com.eventfulcommerce.common.ProductStockUpdatedPayload
import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductLabel
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
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class ProductService(
    private val productRepository: ProductRepository,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper,
    private val imageStorageService: ImageStorageService,
    private val officialSellerRepository: OfficialSellerRepository
) {
    @Transactional(rollbackFor = [Exception::class])
    fun createProduct(request: CreateProductRequest, sellerId: UUID, images: List<MultipartFile>?): ProductResponse {
        if (sellerId == SalesType.OFFICIAL_SELLER_ID) throw AccessDeniedException("공식 판매 주체는 관리자만 사용합니다")
        return registerProduct(request, sellerId, images, SalesType.MARKETPLACE)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(rollbackFor = [Exception::class])
    fun createOfficialProduct(request: CreateProductRequest, images: List<MultipartFile>?): ProductResponse {
        val official = getOfficialSeller()
        return registerProduct(request, official.id, images, SalesType.DIRECT_PURCHASE)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    fun provisionOfficialSeller(displayName: String): OfficialSeller {
        val name = displayName.trim()
        require(name.isNotEmpty() && name.length <= 100) { "공식 판매자명은 1~100자여야 합니다" }
        officialSellerRepository.provision(SalesType.OFFICIAL_SELLER_ID, name)
        return getOfficialSeller()
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    fun getOfficialSeller(): OfficialSeller = officialSellerRepository.findById(SalesType.OFFICIAL_SELLER_ID)
        .orElseThrow { IllegalStateException("관리자가 공식 판매 주체를 먼저 발급해야 합니다") }

    private fun registerProduct(request: CreateProductRequest, sellerId: UUID, images: List<MultipartFile>?, salesType: SalesType): ProductResponse {
        val product = Product(
            sellerId = sellerId,
            salesType = salesType,
            name = request.name,
            description = request.description,
            price = request.price,
            stock = request.stock,
            category = request.category
        ).also {
            it.labels = request.labels.toMutableSet()
            it.imageUrls = images?.let { imageStorageService.replace(it, emptyList()) }?.toMutableList() ?: mutableListOf()
        }
        val saved = productRepository.save(product)

        val payload = ProductRegisteredPayload(
            productId = saved.id,
            sellerId = sellerId,
            name = saved.name,
            price = saved.price,
            initialStock = saved.stock,
            category = saved.category.name,
            salesType = saved.salesType,
            schemaVersion = 2
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

    @Transactional(rollbackFor = [Exception::class])
    fun updateProduct(productId: UUID, request: UpdateProductRequest, sellerId: UUID, images: List<MultipartFile>?): ProductResponse {
        return updateProductInformation(getOwnedProduct(productId, sellerId), request, images)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(rollbackFor = [Exception::class])
    fun updateOfficialProduct(productId: UUID, request: UpdateProductRequest, images: List<MultipartFile>?): ProductResponse =
        updateProductInformation(getOfficialProduct(productId), request, images)

    private fun updateProductInformation(product: Product, request: UpdateProductRequest, images: List<MultipartFile>?): ProductResponse {
        val newImageUrls = images?.let { files ->
            imageStorageService.replace(files, product.imageUrls.toList())
        }
        product.update(request.name, request.description, request.price, request.category, request.labels, newImageUrls)
        outboxEventService.record(listOf(OutboxEvent(
            aggregateType = "PRODUCT",
            aggregateId = product.id,
            eventType = "PRODUCT_UPDATED",
            payload = objectMapper.writeValueAsString(ProductUpdatedPayload(
                product.id, product.sellerId, product.name, product.price,
                product.category.name, product.informationVersion, product.salesType, schemaVersion = 2
            ))
        )))
        return ProductResponse.from(product)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun updateStock(productId: UUID, delta: Int, sellerId: UUID): ProductResponse {
        val product = getOwnedProduct(productId, sellerId)
        product.adjustStock(delta)

        val payload = ProductStockUpdatedPayload(
            productId = product.id,
            sellerId = sellerId,
            stockDelta = delta,
            newStock = product.stock,
            schemaVersion = 2
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

    /** Applies an Order-side Redis transition to Product DB exactly once in the consumer transaction. */
    @Transactional(rollbackFor = [Exception::class])
    fun applyInventoryStockAdjustment(payload: InventoryStockAdjustedPayload, occurredAt: Instant) {
        val product = productRepository.findByIdForUpdate(payload.productId)
            ?: throw ProductNotFoundException(payload.productId)
        check(product.salesType == SalesType.MARKETPLACE) { "직매입은 Redis 대사 대상이 아닙니다" }
        // A newer absolute snapshot already includes this older delta.
        if (product.inventorySnapshotAt?.let { !occurredAt.isAfter(it) } == true) return
        product.adjustStock(payload.stockDelta)
        if (product.lastInventoryAdjustmentAt == null || occurredAt.isAfter(product.lastInventoryAdjustmentAt)) {
            product.lastInventoryAdjustmentAt = occurredAt
        }
        recordRedisSynchronizedStockUpdate(product, payload.stockDelta)
        logger.info {
            "Inventory ledger applied to Product DB: productId=${payload.productId}, orderId=${payload.orderId}, " +
                "reservationId=${payload.reservationId}, delta=${payload.stockDelta}, reason=${payload.reason}"
        }
    }

    /**
     * Absolute reconciliation is only allowed after Order service verified that there are no
     * active Redis holds. Older snapshots cannot overwrite a newer one.
     */
    @Transactional(rollbackFor = [Exception::class])
    fun applyInventoryStockSnapshot(payload: InventoryStockSnapshotPayload, occurredAt: Instant) {
        val product = productRepository.findByIdForUpdate(payload.productId)
            ?: throw ProductNotFoundException(payload.productId)
        check(product.salesType == SalesType.MARKETPLACE) { "직매입은 Redis 대사 대상이 아닙니다" }
        if (product.inventorySnapshotAt?.let { !occurredAt.isAfter(it) } == true) return
        // A snapshot made before the latest delta must not overwrite that newer delta.
        if (product.lastInventoryAdjustmentAt?.let { !occurredAt.isAfter(it) } == true) return

        val delta = payload.availableStock - product.stock
        product.replaceStock(payload.availableStock, occurredAt)
        if (delta != 0) recordRedisSynchronizedStockUpdate(product, delta)
        logger.warn {
            "Inventory snapshot reconciled Product DB: productId=${payload.productId}, " +
                "availableStock=${payload.availableStock}, delta=$delta"
        }
    }

    private fun recordRedisSynchronizedStockUpdate(product: Product, stockDelta: Int) {
        val payload = ProductStockUpdatedPayload(
            productId = product.id,
            sellerId = product.sellerId,
            stockDelta = stockDelta,
            newStock = product.stock,
            redisAlreadyAdjusted = true,
            schemaVersion = 2
        )
        outboxEventService.record(listOf(OutboxEvent(
            aggregateType = "PRODUCT",
            aggregateId = product.id,
            eventType = "PRODUCT_STOCK_UPDATED",
            payload = objectMapper.writeValueAsString(payload),
            status = OutboxStatus.PENDING
        )))
    }

    @Transactional(rollbackFor = [Exception::class])
    fun deactivateProduct(productId: UUID, sellerId: UUID) {
        deactivate(getOwnedProduct(productId, sellerId))
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(rollbackFor = [Exception::class])
    fun deactivateOfficialProduct(productId: UUID) { deactivate(getOfficialProduct(productId)) }

    private fun deactivate(product: Product) {
        val productId = product.id
        val sellerId = product.sellerId
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
    fun getProducts(category: ProductCategory?, label: ProductLabel?): List<ProductResponse> {
        val products = when {
            category != null && label != null -> productRepository.findByStatusAndCategoryAndLabel(ProductStatus.ACTIVE, category, label)
            category != null -> productRepository.findByStatusAndCategory(ProductStatus.ACTIVE, category)
            label != null -> productRepository.findByStatusAndLabel(ProductStatus.ACTIVE, label)
            else -> productRepository.findByStatus(ProductStatus.ACTIVE)
        }
        return products.map { ProductResponse.from(it) }
    }

    @Transactional(readOnly = true)
    fun getProduct(productId: UUID): ProductResponse {
        val product = productRepository.findById(productId)
            .orElseThrow { ProductNotFoundException(productId) }
        if (product.status != ProductStatus.ACTIVE) throw ProductNotFoundException(productId)
        return ProductResponse.from(product)
    }

    @Transactional(readOnly = true)
    fun getMyProducts(sellerId: UUID): List<ProductResponse> =
        productRepository.findBySellerIdAndStatus(sellerId, ProductStatus.ACTIVE)
            .map { ProductResponse.from(it) }

    private fun getOfficialProduct(productId: UUID): Product {
        val product = productRepository.findByIdForUpdate(productId) ?: throw ProductNotFoundException(productId)
        if (product.salesType != SalesType.DIRECT_PURCHASE || product.sellerId != SalesType.OFFICIAL_SELLER_ID)
            throw ProductOwnershipException(productId)
        return product
    }

    private fun getOwnedProduct(productId: UUID, sellerId: UUID): Product {
        val product = productRepository.findByIdForUpdate(productId)
            ?: throw ProductNotFoundException(productId)
        if (product.sellerId != sellerId || product.salesType != SalesType.MARKETPLACE) throw ProductOwnershipException(productId)
        return product
    }
}
