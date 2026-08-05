package com.eventfulcommerce.order.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "order_saga_refund_receipt",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_order_saga_refund_receipt",
            columnNames = ["saga_id", "seller_order_id"]
        )
    ]
)
class OrderSagaRefundReceipt(
    @Column(name = "saga_id", nullable = false)
    val sagaId: UUID,

    @Column(name = "seller_order_id", nullable = false)
    val sellerOrderId: UUID,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}
