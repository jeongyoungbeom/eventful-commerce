package com.eventfulcommerce.payment.domain

import java.util.UUID

data class PaymentWebhookRequest(
    val orderId: UUID,
    val result: String,
    val pgTxId: String? = null,
    val amount: Long? = null,
)