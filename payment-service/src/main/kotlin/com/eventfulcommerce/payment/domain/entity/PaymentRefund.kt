package com.eventfulcommerce.payment.domain.entity

import jakarta.persistence.*
import java.time.Instant
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

    @Column(name = "refunded_at", nullable = false)
    val refundedAt: Instant = Instant.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}
