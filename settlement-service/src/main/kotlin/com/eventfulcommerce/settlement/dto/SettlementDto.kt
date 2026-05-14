package com.eventfulcommerce.settlement.dto

import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.domain.entity.Settlement
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "정산 응답. 판매자 주문 단위로 생성되며 환불 발생 시 금액이 차감됩니다.")
data class SettlementResponse(
    val settlementId: UUID,
    val paymentId: UUID,
    val orderId: UUID,
    val sellerOrderId: UUID,
    val sellerId: UUID,
    val totalAmount: Long,
    val platformFee: Long,
    val sellerAmount: Long,
    val refundedAmount: Long,
    val status: SettlementStatus,
    val confirmedAt: Instant?,
    val paidAt: Instant?,
    val createdAt: Instant
) {
    companion object {
        fun from(s: Settlement) = SettlementResponse(
            settlementId = s.id,
            paymentId = s.paymentId,
            orderId = s.orderId,
            sellerOrderId = s.sellerOrderId,
            sellerId = s.sellerId,
            totalAmount = s.totalAmount,
            platformFee = s.platformFee,
            sellerAmount = s.sellerAmount,
            refundedAmount = s.refundedAmount,
            status = s.status,
            confirmedAt = s.confirmedAt,
            paidAt = s.paidAt,
            createdAt = s.createdAt
        )
    }
}

@Schema(description = "판매자 정산 요약 응답")
data class SettlementSummaryResponse(
    val sellerId: UUID,
    val pendingCount: Int,
    val pendingAmount: Long,
    val confirmedCount: Int,
    val confirmedAmount: Long,
    val paidCount: Int,
    val paidAmount: Long,
    val totalSellerAmount: Long
)
