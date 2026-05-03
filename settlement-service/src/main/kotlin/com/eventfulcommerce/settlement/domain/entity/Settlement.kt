package com.eventfulcommerce.settlement.domain.entity

import com.eventfulcommerce.settlement.domain.SettlementStatus
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "settlements")
class Settlement(
    @Column(nullable = false, unique = true)
    val paymentId: UUID,

    @Column(nullable = false)
    val orderId: UUID,

    @Column(nullable = false)
    val sellerId: UUID,

    @Column(nullable = false)
    val userId: UUID,

    @Column(nullable = false)
    val totalAmount: Long,

    @Column(nullable = false)
    val platformFee: Long,

    @Column(nullable = false)
    val sellerAmount: Long,

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
}
