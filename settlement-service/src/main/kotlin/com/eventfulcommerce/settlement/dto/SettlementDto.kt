package com.eventfulcommerce.settlement.dto

import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.domain.entity.Settlement
import java.time.Instant
import java.util.UUID

data class SettlementResponse(
    val settlementId: UUID,
    val paymentId: UUID,
    val orderId: UUID,
    val sellerId: UUID,
    val totalAmount: Long,
    val platformFee: Long,
    val sellerAmount: Long,
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
            sellerId = s.sellerId,
            totalAmount = s.totalAmount,
            platformFee = s.platformFee,
            sellerAmount = s.sellerAmount,
            status = s.status,
            confirmedAt = s.confirmedAt,
            paidAt = s.paidAt,
            createdAt = s.createdAt
        )
    }
}

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
