package com.eventfulcommerce.order.domain.entity

enum class OrderSagaStatus {
    RESERVED,
    PAYMENT_COMPLETED,
    CONFIRMED,
    COMPENSATION_REQUESTED,
    REFUND_PENDING,
    COMPENSATED,
    COMPENSATION_FAILED
}
