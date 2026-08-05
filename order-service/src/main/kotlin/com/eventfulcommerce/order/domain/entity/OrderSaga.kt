package com.eventfulcommerce.order.domain.entity

import com.eventfulcommerce.common.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "order_saga", uniqueConstraints = [UniqueConstraint(name = "uk_order_saga_order", columnNames = ["order_id"])])
class OrderSaga(
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderSagaStatus = OrderSagaStatus.RESERVED,

    @Column(name = "expected_refund_count", nullable = false)
    var expectedRefundCount: Int = 0,

    @Column(name = "refunded_count", nullable = false)
    var refundedCount: Int = 0,

    @Column(name = "compensation_attempt_count", nullable = false)
    var compensationAttemptCount: Int = 0,

    @Column(name = "reconciliation_attempt_count", nullable = false)
    var reconciliationAttemptCount: Int = 0,

    @Column(name = "last_reconciled_at")
    var lastReconciledAt: Instant? = null,

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,

    @Version
    var version: Long = 0
) : BaseTimeEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}
