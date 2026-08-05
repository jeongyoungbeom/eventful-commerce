package com.eventfulcommerce.product.service

import com.eventfulcommerce.common.BaseTimeEntity
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.InventoryStockAdjustedPayload
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.ProductDeactivatedPayload
import com.eventfulcommerce.common.ProductRegisteredPayload
import com.eventfulcommerce.common.ProductStockUpdatedPayload
import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductLabel
import com.eventfulcommerce.product.domain.ProductStatus
import com.eventfulcommerce.product.domain.entity.Product
import com.eventfulcommerce.product.dto.CreateProductRequest
import com.eventfulcommerce.product.exception.ProductOwnershipException
import com.eventfulcommerce.product.repository.ProductRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ProductServiceTest {
    private lateinit var productRepository: ProductRepository
    private lateinit var outboxEventService: OutboxEventService
    private lateinit var imageStorageService: ImageStorageService
    private lateinit var productService: ProductService

    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        productRepository = mockk()
        outboxEventService = mockk()
        imageStorageService = mockk(relaxed = true)

        every { productRepository.save(any<Product>()) } answers {
            firstArg<Product>().also { assignGeneratedFields(it) }
        }
        every { outboxEventService.record(any<List<OutboxEvent>>()) } just Runs

        productService = ProductService(
            productRepository = productRepository,
            outboxEventService = outboxEventService,
            objectMapper = objectMapper,
            imageStorageService = imageStorageService
        )
    }

    @Test
    fun `상품 등록은 PRODUCT_REGISTERED 아웃박스 이벤트를 기록한다`() {
        val sellerId = UUID.randomUUID()
        val response = productService.createProduct(
            request = CreateProductRequest(
                name = "테스트 상품",
                description = "상품 설명",
                price = 20_000,
                stock = 10,
                category = ProductCategory.FLOWERS,
                labels = setOf(ProductLabel.NEW)
            ),
            sellerId = sellerId,
            images = null
        )

        assertEquals("테스트 상품", response.name)
        assertEquals(ProductStatus.ACTIVE, response.status)
        verify(exactly = 1) {
            outboxEventService.record(
                match<List<OutboxEvent>> { events ->
                    events.size == 1 &&
                        events.single().aggregateType == "PRODUCT" &&
                        events.single().aggregateId == response.productId &&
                        events.single().eventType == "PRODUCT_REGISTERED" &&
                        events.single().status == OutboxStatus.PENDING &&
                        objectMapper.readValue<ProductRegisteredPayload>(events.single().payload).let {
                            it.productId == response.productId &&
                                it.sellerId == sellerId &&
                                it.name == "테스트 상품" &&
                                it.price == 20_000L &&
                                it.initialStock == 10 &&
                                it.category == ProductCategory.FLOWERS.name
                        }
                }
            )
        }
    }

    @Test
    fun `재고 변경은 PRODUCT_STOCK_UPDATED 아웃박스 이벤트를 기록한다`() {
        val sellerId = UUID.randomUUID()
        val product = productFixture(sellerId = sellerId, stock = 10)
        every { productRepository.findByIdForUpdate(product.id) } returns product

        val response = productService.updateStock(product.id, delta = 5, sellerId = sellerId)

        assertEquals(15, response.stock)
        verify(exactly = 1) {
            outboxEventService.record(
                match<List<OutboxEvent>> { events ->
                    events.size == 1 &&
                        events.single().aggregateId == product.id &&
                        events.single().eventType == "PRODUCT_STOCK_UPDATED" &&
                        objectMapper.readValue<ProductStockUpdatedPayload>(events.single().payload).let {
                            it.productId == product.id &&
                                it.sellerId == sellerId &&
                                it.stockDelta == 5 &&
                                it.newStock == 15
                        }
                }
            )
        }
    }

    @Test
    fun `Order Redis 재고 변경 사실은 Product DB와 Redis 생략 플래그가 있는 Product Outbox로 반영된다`() {
        val product = productFixture(sellerId = UUID.randomUUID(), stock = 10)
        every { productRepository.findByIdForUpdate(product.id) } returns product

        productService.applyInventoryStockAdjustment(
            InventoryStockAdjustedPayload(
                orderId = UUID.randomUUID(),
                productId = product.id,
                reservationId = UUID.randomUUID(),
                stockDelta = -2,
                reason = "RESERVED"
            ),
            Instant.now()
        )

        assertEquals(8, product.stock)
        verify(exactly = 1) {
            outboxEventService.record(
                match<List<OutboxEvent>> { events ->
                    events.single().let { event ->
                        event.eventType == "PRODUCT_STOCK_UPDATED" &&
                            objectMapper.readValue<ProductStockUpdatedPayload>(event.payload).let { payload ->
                                payload.stockDelta == -2 &&
                                    payload.newStock == 8 &&
                                    payload.redisAlreadyAdjusted
                            }
                    }
                }
            )
        }
    }

    @Test
    fun `상품 비활성화는 PRODUCT_DEACTIVATED 아웃박스 이벤트를 기록한다`() {
        val sellerId = UUID.randomUUID()
        val product = productFixture(sellerId = sellerId)
        every { productRepository.findById(product.id) } returns Optional.of(product)

        productService.deactivateProduct(product.id, sellerId)

        assertEquals(ProductStatus.INACTIVE, product.status)
        verify(exactly = 1) {
            outboxEventService.record(
                match<List<OutboxEvent>> { events ->
                    events.size == 1 &&
                        events.single().aggregateId == product.id &&
                        events.single().eventType == "PRODUCT_DEACTIVATED" &&
                        objectMapper.readValue<ProductDeactivatedPayload>(events.single().payload).let {
                            it.productId == product.id && it.sellerId == sellerId
                        }
                }
            )
        }
    }

    @Test
    fun `본인 상품이 아니면 재고 변경을 거부하고 이벤트를 기록하지 않는다`() {
        val ownerId = UUID.randomUUID()
        val otherSellerId = UUID.randomUUID()
        val product = productFixture(sellerId = ownerId)
        every { productRepository.findByIdForUpdate(product.id) } returns product

        assertThrows(ProductOwnershipException::class.java) {
            productService.updateStock(product.id, delta = 1, sellerId = otherSellerId)
        }

        verify(exactly = 0) { outboxEventService.record(any<List<OutboxEvent>>()) }
    }

    private fun productFixture(sellerId: UUID, stock: Int = 10): Product {
        val product = Product(
            sellerId = sellerId,
            name = "기존 상품",
            description = "설명",
            price = 10_000,
            stock = stock,
            category = ProductCategory.FLOWERS
        )
        assignGeneratedFields(product)
        return product
    }

    private fun assignGeneratedFields(product: Product) {
        setIfUninitialized(product, "id", UUID.randomUUID())
        val now = Instant.now()
        setIfUninitialized(product, "createdAt", now)
        setIfUninitialized(product, "updatedAt", now)
    }

    private fun setIfUninitialized(target: Any, fieldName: String, value: Any) {
        try {
            if (findField(target, fieldName).get(target) == null) {
                setField(target, fieldName, value)
            }
        } catch (_: UninitializedPropertyAccessException) {
            setField(target, fieldName, value)
        } catch (_: NullPointerException) {
            setField(target, fieldName, value)
        }
    }

    private fun setField(target: Any, fieldName: String, value: Any) {
        findField(target, fieldName).set(target, value)
    }

    private fun findField(target: Any, fieldName: String): java.lang.reflect.Field {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(fieldName)
                field.isAccessible = true
                return field
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("Field not found: ${target.javaClass.name}.$fieldName")
    }
}
