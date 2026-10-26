package com.eventfulcommerce.settlement.domain.entity

import com.eventfulcommerce.common.PaymentRefundedPayload
import jakarta.persistence.*
import org.springframework.data.domain.Persistable
import java.util.UUID

@Entity
@Table(name = "settlement_refund")
class SettlementRefund(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_id", nullable = false)
    val settlement: Settlement,

    @Id
    @Column(name = "refund_id")
    val refundId: UUID,

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false, columnDefinition = "text")
    val reason: String,

    // PostgreSQL timestamp 정밀도에 의해 원본 환불 내용 비교가 달라지지 않게 보존한다.
    @Column(name = "refunded_at", nullable = false)
    val refundedAt: String
) : Persistable<UUID> {
    constructor(settlement: Settlement, payload: PaymentRefundedPayload) : this(
        settlement, payload.refundId, payload.amount, payload.reason, payload.refundedAt.toString()
    )

    @Transient
    private var persisted = false

    override fun getId(): UUID = refundId

    // 환불 기록은 삽입만 허용한다. assigned ID를 merge하면 경합 시 기존 기록을 덮어쓸 수 있다.
    override fun isNew(): Boolean = !persisted

    @PostPersist
    @PostLoad
    private fun markPersisted() {
        persisted = true
    }

    fun matches(target: Settlement, payload: PaymentRefundedPayload): Boolean =
        settlement.id == target.id && amount == payload.amount && reason == payload.reason &&
            refundedAt == payload.refundedAt.toString()
}
