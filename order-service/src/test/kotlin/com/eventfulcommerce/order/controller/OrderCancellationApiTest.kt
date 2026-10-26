package com.eventfulcommerce.order.controller

import com.eventfulcommerce.order.service.OrderCancellationOutcome
import com.eventfulcommerce.order.service.OrdersService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class OrderCancellationApiTest {
    private val service = mockk<OrdersService>()
    private val mvc = MockMvcBuilders.standaloneSetup(OrderController(service)).build()
    private val user = UUID.randomUUID()
    private val order = UUID.randomUUID()
    private val seller = UUID.randomUUID()

    @BeforeEach fun authenticate() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(user, null, emptyList())
    }
    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    @ParameterizedTest
    @CsvSource("PENDING,202,false", "REJECTED,409,false", "CANCELED,200,true", "ALREADY_CANCELED,200,true", "LOCK_UNAVAILABLE,503,false")
    fun `전체와 판매자 취소는 대기와 거절을 HTTP와 outcome으로 구분한다`(name: String, status: Int, success: Boolean) {
        val outcome = OrderCancellationOutcome.valueOf(name)
        every { service.cancelOrder(order, user) } returns outcome
        every { service.cancelSellerOrder(order, seller, user) } returns outcome
        listOf("/orders/$order/cancel", "/orders/$order/seller-orders/$seller/cancel").forEach { path ->
            mvc.perform(post(path)).andExpect(status().`is`(status))
                .andExpect(jsonPath("$.outcome").value(name)).andExpect(jsonPath("$.success").value(success))
        }
    }
}
