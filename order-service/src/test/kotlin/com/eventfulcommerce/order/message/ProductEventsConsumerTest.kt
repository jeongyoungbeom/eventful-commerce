package com.eventfulcommerce.order.message

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.ProductDeactivatedPayload
import com.eventfulcommerce.common.ProductRegisteredPayload
import com.eventfulcommerce.common.ProductStockUpdatedPayload
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.order.domain.entity.ProductReadModel
import com.eventfulcommerce.order.repository.ProductReadModelRepository
import com.eventfulcommerce.order.service.InventoryReservationActionResult
import com.eventfulcommerce.order.service.InventoryReservationService
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ProductEventsConsumerTest {
    private lateinit var productReadModelRepository: ProductReadModelRepository
    private lateinit var inventoryReservationService: InventoryReservationService
    private lateinit var processedEventRepository: ProcessedEventRepository
    private lateinit var consumer: ProductEventsConsumer

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setUp() {
        productReadModelRepository = mockk()
        inventoryReservationService = mockk()
        processedEventRepository = mockk()
        every { processedEventRepository.insertIfAbsent(any()) } returns 1
        consumer = ProductEventsConsumer(
            productReadModelRepository,
            inventoryReservationService,
            IdempotencyHandler(processedEventRepository),
            objectMapper
        )
    }

    @Test
    fun `PRODUCT_REGISTERED 이벤트는 상품 읽기 모델 저장과 Redis 재고 초기화를 수행한다`() {
        val productId = UUID.randomUUID()
        val sellerId = UUID.randomUUID()
        val savedModel = slot<ProductReadModel>()

        every { productReadModelRepository.existsById(productId) } returns false
        every { productReadModelRepository.save(capture(savedModel)) } answers { firstArg() }
        every { inventoryReservationService.initializeStock(productId.toString(), 30) } just Runs

        consumer.consume(
            message(
                eventType = "PRODUCT_REGISTERED",
                payload = ProductRegisteredPayload(
                    productId = productId,
                    sellerId = sellerId,
                    name = "등록 상품",
                    price = 12_000,
                    initialStock = 30,
                    category = "FLOWERS"
                )
            )
        )

        assertEquals(productId, savedModel.captured.productId)
        assertEquals(sellerId, savedModel.captured.sellerId)
        assertEquals("등록 상품", savedModel.captured.name)
        assertEquals(12_000, savedModel.captured.price)
        assertEquals(30, savedModel.captured.stock)
        assertEquals("ACTIVE", savedModel.captured.status)
        verify(exactly = 1) { inventoryReservationService.initializeStock(productId.toString(), 30) }
    }

    @Test
    fun `이미 등록된 상품 이벤트는 읽기 모델과 Redis 재고를 다시 만들지 않는다`() {
        val productId = UUID.randomUUID()

        every { productReadModelRepository.existsById(productId) } returns true

        consumer.consume(
            message(
                eventType = "PRODUCT_REGISTERED",
                payload = ProductRegisteredPayload(
                    productId = productId,
                    sellerId = UUID.randomUUID(),
                    name = "중복 상품",
                    price = 10_000,
                    initialStock = 10,
                    category = "FLOWERS"
                )
            )
        )

        verify(exactly = 0) { productReadModelRepository.save(any<ProductReadModel>()) }
        verify(exactly = 0) { inventoryReservationService.initializeStock(any<String>(), any<Int>()) }
    }

    @Test
    fun `PRODUCT_STOCK_UPDATED 이벤트는 읽기 모델 재고와 Redis 재고를 함께 반영한다`() {
        val productId = UUID.randomUUID()
        val readModel = readModel(productId, stock = 10)

        every { productReadModelRepository.findById(productId) } returns Optional.of(readModel)
        every {
            inventoryReservationService.adjustStockIdempotent(productId.toString(), any(), 5)
        } returns InventoryReservationActionResult.APPLIED

        consumer.consume(
            message(
                eventType = "PRODUCT_STOCK_UPDATED",
                payload = ProductStockUpdatedPayload(
                    productId = productId,
                    sellerId = readModel.sellerId,
                    stockDelta = 5,
                    newStock = 15
                )
            )
        )

        assertEquals(15, readModel.stock)
        verify(exactly = 1) {
            inventoryReservationService.adjustStockIdempotent(productId.toString(), any(), 5)
        }
    }

    @Test
    fun `같은 PRODUCT_STOCK_UPDATED 이벤트를 다시 받으면 Redis 재고를 중복 조정하지 않는다`() {
        val eventId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val readModel = readModel(productId, stock = 10)
        val value = message(
            eventType = "PRODUCT_STOCK_UPDATED",
            payload = ProductStockUpdatedPayload(
                productId = productId,
                sellerId = readModel.sellerId,
                stockDelta = 5,
                newStock = 15
            ),
            eventId = eventId
        )
        every { processedEventRepository.insertIfAbsent(eventId) } returnsMany listOf(1, 0)
        every { productReadModelRepository.findById(productId) } returns Optional.of(readModel)
        every {
            inventoryReservationService.adjustStockIdempotent(productId.toString(), eventId, 5)
        } returns InventoryReservationActionResult.APPLIED

        consumer.consume(value)
        consumer.consume(value)

        assertEquals(15, readModel.stock)
        verify(exactly = 1) { productReadModelRepository.findById(productId) }
        verify(exactly = 1) {
            inventoryReservationService.adjustStockIdempotent(productId.toString(), eventId, 5)
        }
    }

    @Test
    fun `Order Redis에서 이미 반영된 재고 이벤트는 읽기 모델만 갱신하고 Redis를 다시 조정하지 않는다`() {
        val productId = UUID.randomUUID()
        val readModel = readModel(productId, stock = 10)
        every { productReadModelRepository.findById(productId) } returns Optional.of(readModel)

        consumer.consume(
            message(
                eventType = "PRODUCT_STOCK_UPDATED",
                payload = ProductStockUpdatedPayload(
                    productId = productId,
                    sellerId = readModel.sellerId,
                    stockDelta = -2,
                    newStock = 8,
                    redisAlreadyAdjusted = true
                )
            )
        )

        assertEquals(8, readModel.stock)
        verify(exactly = 0) {
            inventoryReservationService.adjustStockIdempotent(any<String>(), any(), any())
        }
    }

    @Test
    fun `PRODUCT_DEACTIVATED 이벤트는 읽기 모델을 비활성화한다`() {
        val productId = UUID.randomUUID()
        val readModel = readModel(productId)

        every { productReadModelRepository.findById(productId) } returns Optional.of(readModel)

        consumer.consume(
            message(
                eventType = "PRODUCT_DEACTIVATED",
                payload = ProductDeactivatedPayload(productId = productId, sellerId = readModel.sellerId)
            )
        )

        assertEquals("INACTIVE", readModel.status)
    }

    @Test
    fun `unsupported product event is not claimed as processed`() {
        val eventId = UUID.randomUUID()

        consumer.consume(
            message(
                eventType = "PRODUCT_UNKNOWN",
                payload = emptyMap<String, String>(),
                eventId = eventId
            )
        )

        verify(exactly = 0) { processedEventRepository.insertIfAbsent(eventId) }
    }

    private fun readModel(productId: UUID, stock: Int = 10) = ProductReadModel(
        productId = productId,
        sellerId = UUID.randomUUID(),
        name = "읽기 모델",
        price = 10_000,
        stock = stock,
        category = "FLOWERS"
    )

    private fun message(eventType: String, payload: Any, eventId: UUID = UUID.randomUUID()): String =
        objectMapper.writeValueAsString(
            OutboxEventMessage(
                eventId = eventId,
                aggregateType = "PRODUCT",
                aggregateId = UUID.randomUUID(),
                eventType = eventType,
                occurredAt = Instant.now(),
                payload = objectMapper.writeValueAsString(payload)
            )
        )
}
