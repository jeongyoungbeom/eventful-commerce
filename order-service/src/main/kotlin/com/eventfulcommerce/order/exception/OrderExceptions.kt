package com.eventfulcommerce.order.exception

sealed class OrderException(message: String) : RuntimeException(message)

class InsufficientInventoryException(orderId: String) : 
    OrderException("재고가 부족하여 주문을 처리할 수 없습니다. orderId=$orderId")

class InvalidOrderStatusException(orderId: String, currentStatus: String, expectedStatus: String) :
    OrderException("잘못된 주문 상태입니다. orderId=$orderId, current=$currentStatus, expected=$expectedStatus")

class OrderNotFoundException(orderId: String) :
    OrderException("주문을 찾을 수 없습니다. orderId=$orderId")
