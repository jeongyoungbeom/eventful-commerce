package com.eventfulcommerce.order.exception

import com.eventfulcommerce.order.domain.OrdersStatus
import java.util.UUID

sealed class OrderException(message: String) : RuntimeException(message)

class InsufficientInventoryException(
    message: String,
    val failedOrderIds: String? = null
) : OrderException(message)

class InvalidOrderStatusException(
    val orderId: UUID,
    val currentStatus: OrdersStatus,
    val expectedStatus: OrdersStatus
) : OrderException("잘못된 주문 상태입니다. orderId=$orderId, current=$currentStatus, expected=$expectedStatus")

class OrderNotFoundException(
    val orderId: UUID
) : OrderException("주문을 찾을 수 없습니다. orderId=$orderId")
