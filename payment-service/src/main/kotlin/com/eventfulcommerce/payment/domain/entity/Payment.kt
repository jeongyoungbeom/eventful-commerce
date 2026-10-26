package com.eventfulcommerce.payment.domain.entity

import com.eventfulcommerce.payment.domain.PaymentStatus
import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(
    name = "payment",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_payment_order", columnNames = ["order_id"]),
        UniqueConstraint(name = "uk_payment_pg_tx", columnNames = ["pg_tx_id"])
    ]
)
class Payment(
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentStatus,

    @Column(nullable = false)
    val amount: Long,

    @Lob
    @Column(nullable = false)
    val sellerOrdersJson: String,

    @Column(nullable = false)
    var refundedAmount: Long = 0,

    @Column(name = "cancellation_requested", nullable = false)
    var cancellationRequested: Boolean = false,

    // 판매자 주문별 취소 payload 배열. 최초 취소 사유를 보존한다.
    @Column(name = "pending_cancellation_payload", columnDefinition = "text")
    var pendingCancellationPayload: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @Column(name = "pg_tx_id", length = 200)
    var pgTxId: String? = null

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
