package com.eventfulcommerce.order.request

import com.fasterxml.jackson.annotation.JsonAnySetter
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero

data class OrderConfirmationRequest(
    @field:Positive val compositionVersion: Long,
    @field:PositiveOrZero val totalPaymentAmount: Long
) {
    @JsonAnySetter fun rejectUnknown(name: String, value: Any?) {
        throw IllegalArgumentException("허용되지 않은 확인 필드: $name")
    }
}
