package com.eventfulcommerce.order.scheduler

import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.repository.OrdersRepository
import com.eventfulcommerce.order.repository.ProductReadModelRepository
import com.eventfulcommerce.order.service.InventoryReservationActionResult
import com.eventfulcommerce.order.service.InventoryReservationMetadata
import com.eventfulcommerce.order.service.InventoryReservationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

class InventoryReservationCleanupSchedulerTest {
    private lateinit var ordersRepository: OrdersRepository
    private lateinit var productReadModelRepository: ProductReadModelRepository
    private lateinit var inventoryReservationService: InventoryReservationService
    private lateinit var scheduler: InventoryReservationCleanupScheduler

    @BeforeEach
    fun setUp() {
        ordersRepository = mockk()
        productReadModelRepository = mockk()
        inventoryReservationService = mockk()
        scheduler = InventoryReservationCleanupScheduler(
            ordersRepository,
            productReadModelRepository,
            inventoryReservationService,
            batchSize = 100,
            failureBackoffSeconds = 60
        )
        every { productReadModelRepository.findAllProductIds() } returns emptyList()
        every { inventoryReservationService.registerReservationProducts(any()) } returns Unit
    }

    @Test
    fun `expired hold without an order is released as an orphan`() {
        val productId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val metadata = InventoryReservationMetadata(
            orderId = UUID.randomUUID(),
            productId = productId.toString(),
            reservationId = reservationId,
            quantity = 3,
            expiresAt = Instant.now().minusSeconds(1)
        )
        every { inventoryReservationService.findReservationProductIds() } returns emptyList()
        every { productReadModelRepository.findAllProductIds() } returns listOf(productId)
        every {
            inventoryReservationService.findExpiredReservationIds(productId.toString(), any(), 100)
        } returns listOf(reservationId)
        every {
            inventoryReservationService.getReservationMetadata(productId.toString(), reservationId)
        } returns metadata
        every { ordersRepository.findById(metadata.orderId) } returns Optional.empty()
        every {
            inventoryReservationService.release(productId.toString(), reservationId, metadata.quantity)
        } returns InventoryReservationActionResult.APPLIED

        scheduler.cleanupExpiredReservations()

        verify(exactly = 1) {
            inventoryReservationService.release(productId.toString(), reservationId, metadata.quantity)
        }
        verify(exactly = 1) {
            inventoryReservationService.registerReservationProducts(listOf(productId))
        }
    }

    @Test
    fun `active reserved order is left to the order expiration flow`() {
        val productId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        val order = Orders(
            userId = UUID.randomUUID(),
            status = OrdersStatus.ORDER_RESERVED,
            expiresAt = Instant.now().plusSeconds(60)
        )
        setOrderId(order, UUID.randomUUID())
        val metadata = InventoryReservationMetadata(
            orderId = order.id,
            productId = productId.toString(),
            reservationId = reservationId,
            quantity = 1,
            expiresAt = Instant.now().minusSeconds(1)
        )
        every { inventoryReservationService.findReservationProductIds() } returns listOf(productId)
        every {
            inventoryReservationService.findExpiredReservationIds(productId.toString(), any(), 100)
        } returns listOf(reservationId)
        every {
            inventoryReservationService.getReservationMetadata(productId.toString(), reservationId)
        } returns metadata
        every { ordersRepository.findById(order.id) } returns Optional.of(order)
        every {
            inventoryReservationService.removeExpirationIndexEntry(productId.toString(), reservationId)
        } returns Unit

        scheduler.cleanupExpiredReservations()

        verify(exactly = 0) {
            inventoryReservationService.release(any(), any(), any())
        }
        verify(exactly = 0) {
            inventoryReservationService.commit(any(), any(), any())
        }
        verify(exactly = 1) {
            inventoryReservationService.removeExpirationIndexEntry(productId.toString(), reservationId)
        }
    }

    @Test
    fun `failed cleanup is rescheduled with backoff so later holds are not starved`() {
        val productId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        every { inventoryReservationService.findReservationProductIds() } returns listOf(productId)
        every {
            inventoryReservationService.findExpiredReservationIds(productId.toString(), any(), 100)
        } returns listOf(reservationId)
        every {
            inventoryReservationService.getReservationMetadata(productId.toString(), reservationId)
        } throws IllegalStateException("corrupted hold")
        every {
            inventoryReservationService.rescheduleExpirationIndexEntry(
                productId.toString(),
                reservationId,
                any()
            )
        } returns Unit

        val startedAt = Instant.now()
        scheduler.cleanupExpiredReservations()

        verify(exactly = 1) {
            inventoryReservationService.rescheduleExpirationIndexEntry(
                productId.toString(),
                reservationId,
                match { retryAt -> retryAt >= startedAt.plusSeconds(60) }
            )
        }
    }

    private fun setOrderId(order: Orders, orderId: UUID) {
        val field = Orders::class.java.getDeclaredField("id")
        field.isAccessible = true
        field.set(order, orderId)
    }
}
