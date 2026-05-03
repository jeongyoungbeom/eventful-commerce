package com.eventfulcommerce.order.controller

import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.service.OrdersService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
class OrderController(
    private val ordersService: OrdersService
) {

    @PostMapping("/orders")
    fun orders(@RequestBody ordersRequests: List<OrdersRequest>): ResponseEntity<List<String>> {
        val userId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity(ordersService.orders(ordersRequests, userId), HttpStatus.OK)
    }

    @PostMapping("/orders/{orderId}/cancel")
    fun cancelOrder(@PathVariable orderId: UUID): ResponseEntity<Map<String, Any>> {
        val userId = SecurityContextUtil.getCurrentUserId()
        val success = ordersService.cancelOrder(orderId, userId)

        return if (success) {
            ResponseEntity.ok(mapOf(
                "success" to true,
                "orderId" to orderId,
                "message" to "주문이 취소되었습니다"
            ))
        } else {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "orderId" to orderId,
                "message" to "주문 취소 실패 (이미 처리되었거나 취소 불가능한 상태)"
            ))
        }
    }
}
