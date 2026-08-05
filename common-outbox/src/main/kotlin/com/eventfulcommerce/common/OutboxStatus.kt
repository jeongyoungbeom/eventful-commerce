package com.eventfulcommerce.common

enum class OutboxStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED
}
