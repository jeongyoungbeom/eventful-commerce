package com.eventfulcommerce.orderservice.controller

import com.eventfulcommerce.orderservice.application.CreateOrderRequest
import com.eventfulcommerce.orderservice.application.CreateOrderResponse
import com.eventfulcommerce.orderservice.application.OrderSagaService
import com.eventfulcommerce.orderservice.domain.OrderRepository
import com.eventfulcommerce.orderservice.domain.OrderStatus
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class OrderStatusResponse(
    val orderId: UUID,
    val status: OrderStatus,
)

@RestController
@RequestMapping("/orders")
class OrderController(
    private val orderSagaService: OrderSagaService,
    private val orderRepository: OrderRepository,
) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateOrderRequest): CreateOrderResponse =
        orderSagaService.createOrder(request)

    @GetMapping("/{orderId}")
    fun get(@PathVariable orderId: UUID): ResponseEntity<OrderStatusResponse> {
        val order = orderRepository.findById(orderId)
        return if (order.isPresent) {
            ResponseEntity.ok(OrderStatusResponse(orderId, order.get().status))
        } else {
            ResponseEntity.notFound().build()
        }
    }
}
