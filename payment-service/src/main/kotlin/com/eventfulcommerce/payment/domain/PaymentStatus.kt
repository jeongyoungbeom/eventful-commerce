package com.eventfulcommerce.payment.domain

enum class PaymentStatus{
    PAYMENT,
    PAYMENT_RESERVED,
    PAYMENT_COMPLETED,
    PAYMENT_PARTIALLY_REFUNDED,
    PAYMENT_REFUNDED,
    PAYMENT_FAILED,
    PAYMENT_REJECTED
}
