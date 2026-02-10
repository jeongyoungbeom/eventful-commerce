package com.eventfulcommerce.order.controller

import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.service.OrdersService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class OrderController(
    private val ordersService: OrdersService
) {

    @PostMapping("/orders")
    fun orders(@RequestBody ordersRequests: List<OrdersRequest>): ResponseEntity<List<String>> {
        return ResponseEntity( ordersService.orders(ordersRequests), HttpStatus.OK)
    }
}