package com.eventfulcommerce.shipping.domain

enum class ShippingStatus {
    PREPARING,    // 배송 준비 중
    STARTED,      // 배송 시작
    COMPLETED     // 배송 완료
}