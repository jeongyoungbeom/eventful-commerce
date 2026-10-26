package com.eventfulcommerce.settlement.domain.entity

import com.eventfulcommerce.settlement.domain.SettlementStatus
import jakarta.persistence.*
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "settlements")
class Settlement(
    @Column(nullable = false)
    val paymentId: UUID,

    @Column(nullable = false)
    val orderId: UUID,

    @Column(nullable = false, unique = true)
    val sellerOrderId: UUID,

    @Column(nullable = false)
    val sellerId: UUID,

    @Column(nullable = false)
    val userId: UUID,

    @Column(nullable = false)
    var totalAmount: Long,

    @Column(nullable = false)
    var platformFee: Long,

    @Column(nullable = false)
    var sellerAmount: Long,

    @Column(nullable = false)
    var refundedAmount: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SettlementStatus = SettlementStatus.PENDING,

    @Column(nullable = true)
    var confirmedAt: Instant? = null,

    @Column(nullable = true)
    var paidAt: Instant? = null,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @Version
    @Column(nullable = false)
    var version: Long = 0

    fun confirm() {
        require(status == SettlementStatus.PENDING) { "PENDING 상태만 확정할 수 있습니다" }
        status = SettlementStatus.CONFIRMED
        confirmedAt = Instant.now()
    }

    fun pay() {
        require(status == SettlementStatus.CONFIRMED) { "CONFIRMED 상태만 지급 완료 처리할 수 있습니다" }
        status = SettlementStatus.PAID
        paidAt = Instant.now()
    }

    fun applyRefund(refundAmount: Long) {
        require(refundAmount > 0 && refundAmount <= totalAmount) { "환불 금액은 양수이고 잔여 정산 금액 이하여야 합니다" }
        require(platformFee in 0..totalAmount && sellerAmount == totalAmount - platformFee) {
            "정산 수수료와 판매자 금액의 합이 잔여 금액과 일치해야 합니다"
        }
        require(refundedAmount >= 0 && refundedAmount <= Long.MAX_VALUE - refundAmount) { "누적 환불 금액 범위를 초과했습니다" }

        // 실제 정산의 잔여 비율로 배분한다. 마지막 환불은 반올림 잔액까지 전부 소진한다.
        val refundPlatformFee = BigInteger.valueOf(refundAmount)
            .multiply(BigInteger.valueOf(platformFee))
            .divide(BigInteger.valueOf(totalAmount)).longValueExact()
        val refundSellerAmount = refundAmount - refundPlatformFee
        refundedAmount += refundAmount
        totalAmount -= refundAmount
        platformFee -= refundPlatformFee
        sellerAmount -= refundSellerAmount
        status = if (totalAmount == 0L) SettlementStatus.REFUNDED else SettlementStatus.PARTIALLY_REFUNDED
    }
}
