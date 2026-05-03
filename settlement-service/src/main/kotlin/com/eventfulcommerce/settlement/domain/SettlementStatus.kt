package com.eventfulcommerce.settlement.domain

enum class SettlementStatus {
    PENDING,    // 결제 완료 수신 → 정산 대기
    CONFIRMED,  // 일별 배치 확정
    PAID        // 지급 완료 (관리자 확인)
}
