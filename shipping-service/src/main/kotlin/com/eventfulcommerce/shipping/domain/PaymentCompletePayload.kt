package com.eventfulcommerce.shipping.domain

import java.util.UUID

data class PaymentCompletePayload(
    val paymentId: UUID,
    val orderId: UUID,
    val amount: Long,
    val status: String
    )