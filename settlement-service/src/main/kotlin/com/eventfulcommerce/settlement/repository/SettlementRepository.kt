package com.eventfulcommerce.settlement.repository

import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.domain.entity.Settlement
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface SettlementRepository : JpaRepository<Settlement, UUID> {
    fun existsByPaymentId(paymentId: UUID): Boolean
    fun findByPaymentId(paymentId: UUID): Settlement?
    fun findBySellerIdOrderByCreatedAtDesc(sellerId: UUID): List<Settlement>
    fun findBySellerIdAndStatusOrderByCreatedAtDesc(sellerId: UUID, status: SettlementStatus): List<Settlement>
    fun findByStatusAndCreatedAtBefore(status: SettlementStatus, before: Instant): List<Settlement>
    fun findByStatus(status: SettlementStatus): List<Settlement>

    // DB 집계 쿼리로 OOM 없이 요약 조회
    @Query("""
        SELECT s.status as status, COUNT(s) as count, COALESCE(SUM(s.sellerAmount), 0) as totalAmount
        FROM Settlement s
        WHERE s.sellerId = :sellerId
        GROUP BY s.status
    """)
    fun findSummaryBySellerId(@Param("sellerId") sellerId: UUID): List<SettlementSummaryRow>
}

interface SettlementSummaryRow {
    val status: SettlementStatus
    val count: Long
    val totalAmount: Long
}
