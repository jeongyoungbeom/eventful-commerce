package com.eventfulcommerce.order.service

import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.*

@DisplayName("주문 만료 서비스 테스트")
class OrderExpirationServiceTest {

    private lateinit var orderExpirationService: OrderExpirationService
    private lateinit var orderCancelService: OrderCancelService

    @BeforeEach
    fun setUp() {
        orderCancelService = mockk()
        orderExpirationService = OrderExpirationService(
            orderCancelService = orderCancelService
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    @DisplayName("주문 만료 처리가 OrderCancelService를 호출한다")
    fun `should call OrderCancelService when expiring order`() {
        // Given
        val orderId = UUID.randomUUID()
        every { orderCancelService.cancel(orderId, "주문 만료") } returns true

        // When
        orderExpirationService.expireOrder(orderId)

        // Then
        verify(exactly = 1) { orderCancelService.cancel(orderId, "주문 만료") }
    }

    @Test
    @DisplayName("주문 만료 실패 시에도 정상 처리된다")
    fun `should handle failure gracefully`() {
        // Given
        val orderId = UUID.randomUUID()
        every { orderCancelService.cancel(orderId, "주문 만료") } returns false

        // When
        orderExpirationService.expireOrder(orderId)

        // Then
        verify(exactly = 1) { orderCancelService.cancel(orderId, "주문 만료") }
    }

    @Test
    @DisplayName("여러 주문을 순차적으로 만료 처리한다")
    fun `should expire multiple orders sequentially`() {
        // Given
        val orderIds = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        every { orderCancelService.cancel(any(), "주문 만료") } returns true

        // When
        orderIds.forEach { orderExpirationService.expireOrder(it) }

        // Then
        orderIds.forEach { orderId ->
            verify(exactly = 1) { orderCancelService.cancel(orderId, "주문 만료") }
        }
    }
}
