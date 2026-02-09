package com.eventfulcommerce.common

import java.util.UUID

data class PaymentCompletedPayload(
    val paymentId: UUID,
    val orderId: UUID,
    val amount: Long,
    val status: String
)