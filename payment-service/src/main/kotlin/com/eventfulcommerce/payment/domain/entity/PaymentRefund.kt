package com.eventfulcommerce.payment.domain.entity

import jakarta.persistence.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Table(
    name = "payment_refund",
    uniqueConstraints = [UniqueConstraint(columnNames = ["payment_id", "seller_order_id"])]
)
class PaymentRefund(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    val payment: Payment,

    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Column(name = "seller_order_id", nullable = false)
    val sellerOrderId: UUID,

    @Column(name = "seller_id", nullable = false)
    val sellerId: UUID,

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false)
    val reason: String,

    refundedAt: Instant = Instant.now()
) {
    // 최초 발행과 DB 재조회 후 재발행이 같은 시각을 사용하도록 저장 정밀도에 맞춘다.
    @Column(name = "refunded_at", nullable = false)
    val refundedAt: Instant = refundedAt.truncatedTo(ChronoUnit.MICROS)

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}
