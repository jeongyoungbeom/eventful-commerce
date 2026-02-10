package com.eventfulcommerce.payment.domain

enum class PaymentStatus{
    PAYMENT,
    PAYMENT_RESERVED,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    PAYMENT_REJECTED
}