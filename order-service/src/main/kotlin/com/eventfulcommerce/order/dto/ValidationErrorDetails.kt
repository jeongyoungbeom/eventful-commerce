package com.eventfulcommerce.order.dto

data class ValidationErrorDetails(
    val errors: List<FieldValidationError>
)

data class FieldValidationError(
    val field: String,
    val message: String
)
