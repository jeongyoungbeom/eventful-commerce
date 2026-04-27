package com.eventfulcommerce.notification.domain

enum class NotificationType {
    ORDER_RESERVED,      // 주문 접수
    PAYMENT_COMPLETED,   // 결제 완료
    ORDER_CANCELED,      // 주문 취소
    SHIPPING_STARTED,    // 배송 시작
    SHIPPING_COMPLETED   // 배송 완료
}
