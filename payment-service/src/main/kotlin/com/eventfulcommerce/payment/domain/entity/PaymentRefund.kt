package com.eventfulcommerce.payment.domain.entity

import jakarta.persistence.*
import com.eventfulcommerce.common.SalesType
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Entity
@Table(
    name = "payment_refund",
    uniqueConstraints = [UniqueConstraint(name = "uk_payment_refund_target", columnNames = ["payment_id", "refund_key"])]
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

    refundedAt: Instant = Instant.now(),
    @Column val orderItemId: UUID? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) val salesType: SalesType = SalesType.MARKETPLACE,
    @Column(columnDefinition = "text") val requestContent: String? = null
) {
    @Column(nullable = false)
    val refundKey: UUID = orderItemId ?: sellerOrderId

    init {
        salesType.validateSeller(sellerId)
        require((salesType == SalesType.DIRECT_PURCHASE) == (orderItemId != null))
    }

    // 최초 발행과 DB 재조회 후 재발행이 같은 시각을 사용하도록 저장 정밀도에 맞춘다.
    @Column(name = "refunded_at", nullable = false)
    val refundedAt: Instant = refundedAt.truncatedTo(ChronoUnit.MICROS)

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}
