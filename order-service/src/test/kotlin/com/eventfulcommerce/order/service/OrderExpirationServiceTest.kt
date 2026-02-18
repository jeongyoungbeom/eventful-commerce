package com.eventfulcommerce.order.service

import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.repository.OrdersRepository
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

@DisplayName("주문 만료 서비스 테스트")
class OrderExpirationServiceTest {

    private lateinit var orderExpirationService: OrderExpirationService
    private lateinit var ordersRepository: OrdersRepository
    private lateinit var inventoryReservationService: InventoryReservationService

    @BeforeEach
    fun setUp() {
        ordersRepository = mockk()
        inventoryReservationService = mockk()

        orderExpirationService = OrderExpirationService(
            ordersRepository = ordersRepository,
            inventoryReservationService = inventoryReservationService
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    @DisplayName("ORDER_RESERVED 상태의 주문이 만료된다")
    fun `should expire order in RESERVED status`() {
        // Given
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        
        val order = Orders(
            userId = UUID.randomUUID(),
            totalAmount = 10000L,
            status = OrdersStatus.ORDER_RESERVED,
            reservationId = reservationId,
            expiresAt = Instant.now().minusSeconds(10)
        ).apply {
            id = orderId
        }

        every { ordersRepository.findById(orderId) } returns Optional.of(order)
        every { inventoryReservationService.release(reservationId) } just Runs
        every { ordersRepository.save(any()) } returns order

        // When
        orderExpirationService.expireOrder(orderId)

        // Then
        assertEquals(OrdersStatus.ORDER_EXPIRED, order.status)
        verify(exactly = 1) { inventoryReservationService.release(reservationId) }
        verify(exactly = 1) { ordersRepository.save(order) }
    }

            @Test
            @DisplayName("이미 CONFIRMED 상태면 만료하지 않는다")
            fun `should not expire order already in CONFIRMED status`() {
                // Given
                val orderId = UUID.randomUUID()
                val order = Orders(
                    userId = UUID.randomUUID(),
                    totalAmount = 10000L,
                    status = OrdersStatus.ORDER_CONFIRMED
                ).apply {
                    id = orderId
                }

                every { ordersRepository.findById(orderId) } returns Optional.of(order)

        // When
        orderExpirationService.expireOrder(orderId)

        // Then
        assertEquals(OrdersStatus.ORDER_CONFIRMED, order.status)
        verify(exactly = 0) { inventoryReservationService.release(any()) }
        verify(exactly = 0) { ordersRepository.save(any()) }
    }

    @Test
    @DisplayName("주문이 존재하지 않으면 아무 작업도 하지 않는다")
    fun `should do nothing when order not found`() {
        // Given
        val orderId = UUID.randomUUID()
        every { ordersRepository.findById(orderId) } returns Optional.empty()

        // When
        orderExpirationService.expireOrder(orderId)

        // Then
        verify(exactly = 0) { inventoryReservationService.release(any()) }
        verify(exactly = 0) { ordersRepository.save(any()) }
    }

    @Test
    @DisplayName("reservationId가 없어도 만료 처리된다")
    fun `should expire order even without reservationId`() {
        // Given
        val orderId = UUID.randomUUID()
        val order = Orders(
            userId = UUID.randomUUID(),
            totalAmount = 10000L,
            status = OrdersStatus.ORDER_RESERVED,
            reservationId = null
        ).apply {
            id = orderId
        }

        every { ordersRepository.findById(orderId) } returns Optional.of(order)
        every { ordersRepository.save(any()) } returns order

        // When
        orderExpirationService.expireOrder(orderId)

        // Then
        assertEquals(OrdersStatus.ORDER_EXPIRED, order.status)
        verify(exactly = 0) { inventoryReservationService.release(any()) }
        verify(exactly = 1) { ordersRepository.save(order) }
    }
}
