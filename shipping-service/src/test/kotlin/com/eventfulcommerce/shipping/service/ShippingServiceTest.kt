package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OrderConfirmedPayload
import com.eventfulcommerce.common.OrderConfirmedSellerPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.ShippingCompletedPayload
import com.eventfulcommerce.common.ShippingStartedPayload
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.shipping.domain.Shipping
import com.eventfulcommerce.shipping.domain.ShippingStatus
import com.eventfulcommerce.shipping.repository.ShippingRepository
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

class ShippingServiceTest {
    private lateinit var processedEventRepository: ProcessedEventRepository
    private lateinit var shippingRepository: ShippingRepository
    private lateinit var outboxEventService: OutboxEventService
    private lateinit var shippingCompletionService: ShippingCompletionService
    private lateinit var shippingCompletionWorker: ShippingCompletionWorker
    private lateinit var shippingService: ShippingService

    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeEach
    fun setUp() {
        processedEventRepository = mockk()
        shippingRepository = mockk()
        outboxEventService = mockk()
        shippingCompletionService = ShippingCompletionService(shippingRepository, outboxEventService, objectMapper)
        shippingCompletionWorker = mockk(relaxed = true)
        shippingService = ShippingService(
            idempotencyHandler = IdempotencyHandler(processedEventRepository),
            shippingRepository = shippingRepository,
            outboxEventService = outboxEventService,
            objectMapper = objectMapper,
            shippingCompletionWorker = shippingCompletionWorker
        )
    }

    @Test
    fun `ORDER_CONFIRMED 이벤트는 판매자 주문별 배송을 생성하고 배송 시작 이벤트를 기록한다`() {
        val payload = orderConfirmedPayload()
        val shippingSlot = slot<Shipping>()

        every { processedEventRepository.insertIfAbsent(any()) } returns 1
        every { shippingRepository.existsBySellerOrderId(payload.sellerOrders.single().sellerOrderId) } returns false
        every { shippingRepository.save(capture(shippingSlot)) } answers {
            firstArg<Shipping>().also { setIfUninitialized(it, "id", UUID.randomUUID()) }
        }
        every { shippingRepository.findById(any<UUID>()) } answers { Optional.of(shippingSlot.captured) }
        every { outboxEventService.record(any<List<OutboxEvent>>()) } just Runs

        shippingService.handleOrderConfirmed(UUID.randomUUID(), objectMapper.writeValueAsString(payload))

        assertEquals(payload.orderId, shippingSlot.captured.orderId)
        assertEquals(payload.sellerOrders.single().sellerOrderId, shippingSlot.captured.sellerOrderId)
        assertEquals(payload.userId, shippingSlot.captured.userId)
        assertNotNull(shippingSlot.captured.trackingNumber)
        verify(exactly = 1) {
            outboxEventService.record(
                match<List<OutboxEvent>> { events ->
                    events.size == 1 &&
                        events.single().eventType == "SHIPPING_STARTED" &&
                        events.single().status == OutboxStatus.PENDING &&
                        objectMapper.readValue<ShippingStartedPayload>(events.single().payload).let {
                            it.orderId == payload.orderId && it.userId == payload.userId
                        }
                }
            )
        }
        verify(exactly = 1) { shippingCompletionWorker.completeAfterDelay(shippingSlot.captured.id) }
    }

    @Test
    fun `이미 처리된 이벤트는 배송을 만들지 않는다`() {
        val payload = orderConfirmedPayload()
        every { processedEventRepository.insertIfAbsent(any()) } returns 0

        shippingService.handleOrderConfirmed(UUID.randomUUID(), objectMapper.writeValueAsString(payload))

        verify(exactly = 0) { shippingRepository.save(any<Shipping>()) }
        verify(exactly = 0) { outboxEventService.record(any<List<OutboxEvent>>()) }
    }

    @Test
    fun `이미 배송이 있는 판매자 주문은 중복 배송을 생성하지 않는다`() {
        val payload = orderConfirmedPayload()
        every { processedEventRepository.insertIfAbsent(any()) } returns 1
        every { shippingRepository.existsBySellerOrderId(payload.sellerOrders.single().sellerOrderId) } returns true

        shippingService.handleOrderConfirmed(UUID.randomUUID(), objectMapper.writeValueAsString(payload))

        verify(exactly = 0) { shippingRepository.save(any<Shipping>()) }
        verify(exactly = 0) { outboxEventService.record(any<List<OutboxEvent>>()) }
    }

    @Test
    fun `배송 완료는 상태를 COMPLETED로 바꾸고 SHIPPING_COMPLETED 이벤트를 기록한다`() {
        val shipping = shippingFixture(status = ShippingStatus.STARTED)
        every { shippingRepository.findByIdForUpdate(shipping.id) } returns shipping
        every { shippingRepository.save(any<Shipping>()) } answers { firstArg() }
        every { outboxEventService.record(any<List<OutboxEvent>>()) } just Runs

        shippingCompletionService.complete(shipping.id)

        assertEquals(ShippingStatus.COMPLETED, shipping.status)
        assertNotNull(shipping.completedAt)
        verify(exactly = 1) {
            outboxEventService.record(
                match<List<OutboxEvent>> { events ->
                    events.size == 1 &&
                        events.single().eventType == "SHIPPING_COMPLETED" &&
                        objectMapper.readValue<ShippingCompletedPayload>(events.single().payload).let {
                            it.orderId == shipping.orderId && it.userId == shipping.userId
                        }
                }
            )
        }
    }

    private fun orderConfirmedPayload() = OrderConfirmedPayload(
        orderId = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        totalAmount = 20_000,
        sellerOrders = listOf(
            OrderConfirmedSellerPayload(
                sellerOrderId = UUID.randomUUID(),
                sellerId = UUID.randomUUID(),
                paymentAmount = 20_000
            )
        ),
        confirmedAt = java.time.Instant.now()
    )

    private fun shippingFixture(status: ShippingStatus): Shipping {
        val shipping = Shipping(
            orderId = UUID.randomUUID(),
            sellerOrderId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            status = status,
            trackingNumber = "TRK123456789"
        )
        setIfUninitialized(shipping, "id", UUID.randomUUID())
        return shipping
    }

    private fun setIfUninitialized(target: Any, fieldName: String, value: Any) {
        try {
            if (findField(target, fieldName).get(target) == null) {
                findField(target, fieldName).set(target, value)
            }
        } catch (_: UninitializedPropertyAccessException) {
            findField(target, fieldName).set(target, value)
        } catch (_: NullPointerException) {
            findField(target, fieldName).set(target, value)
        }
    }

    private fun findField(target: Any, fieldName: String): java.lang.reflect.Field {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field
    }
}
