package com.eventfulcommerce.order.domain

enum class OrdersStatus {
    ORDER,
    ORDER_RESERVED,
    ORDER_CONFIRMED,
    ORDER_PARTIALLY_CANCELED,
    ORDER_EXPIRED,
    ORDER_CANCELED,
    ORDER_FAILED
}
