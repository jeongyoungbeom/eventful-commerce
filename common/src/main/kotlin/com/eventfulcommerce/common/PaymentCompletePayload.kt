package com.eventfulcommerce.common

data class PaymentCompletedPayload(
    val paymentId: String,
    val orderId: String,
    val amount: Long,
    val status: String
)