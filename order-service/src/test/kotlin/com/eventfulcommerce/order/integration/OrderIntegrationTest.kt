package com.eventfulcommerce.order.integration

import com.eventfulcommerce.order.controller.OrderController
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.exception.GlobalExceptionHandler
import com.eventfulcommerce.order.exception.InsufficientInventoryException
import com.eventfulcommerce.order.service.OrdersService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

@DisplayName("주문 API 테스트")
class OrderIntegrationTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var objectMapper: ObjectMapper
    private lateinit var ordersService: OrdersService

    @BeforeEach
    fun setUp() {
        ordersService = mockk()
        objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
        mockMvc = MockMvcBuilders
            .standaloneSetup(OrderController(ordersService))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    @DisplayName("POST /orders 요청이 성공하면 주문 ID 목록을 반환한다")
    fun `should create orders via API`() {
        val orderId = UUID.randomUUID().toString()
        val request = listOf(
            OrdersRequest(
                userId = UUID.randomUUID().toString(),
                productId = "PRODUCT-001",
                totalAmount = 10000L
            )
        )

        every { ordersService.orders(any()) } returns listOf(orderId)

        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0]").value(orderId))

        verify(exactly = 1) { ordersService.orders(request) }
    }

    @Test
    @DisplayName("재고 부족이면 409 Conflict와 에러 코드를 반환한다")
    fun `should return conflict when stock is insufficient`() {
        val failedOrderId = UUID.randomUUID().toString()
        val request = listOf(
            OrdersRequest(
                userId = UUID.randomUUID().toString(),
                productId = "PRODUCT-001",
                totalAmount = 10000L
            )
        )

        every { ordersService.orders(any()) } throws InsufficientInventoryException(
            message = "재고 부족으로 전체 주문이 취소되었습니다. 실패 주문: $failedOrderId",
            failedOrderIds = failedOrderId
        )

        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_INVENTORY"))
            .andExpect(jsonPath("$.details").value(failedOrderId))
    }

    @Test
    @DisplayName("POST /orders/{orderId}/cancel 요청이 성공하면 성공 응답을 반환한다")
    fun `should cancel order via API`() {
        val orderId = UUID.randomUUID()

        every { ordersService.cancelOrder(orderId) } returns true

        mockMvc.perform(post("/orders/$orderId/cancel"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.orderId").value(orderId.toString()))
    }

    @Test
    @DisplayName("취소 실패 시 400 Bad Request를 반환한다")
    fun `should return bad request when cancel fails`() {
        val orderId = UUID.randomUUID()

        every { ordersService.cancelOrder(orderId) } returns false

        mockMvc.perform(post("/orders/$orderId/cancel"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.orderId").value(orderId.toString()))
    }
}
