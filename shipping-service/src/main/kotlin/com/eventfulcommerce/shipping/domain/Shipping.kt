package com.eventfulcommerce.shipping.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(
    name = "shipping",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_shipping_seller_order", columnNames = ["seller_order_id"])
    ]
)
class Shipping(
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Column(name = "seller_order_id", nullable = false)
    val sellerOrderId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ShippingStatus = ShippingStatus.PREPARING,

    @Column(name = "tracking_number")
    var trackingNumber: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "shipped_at")
    var shippedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null
) {

    @Column(name = "cancellation_request_id", unique = true)
    var cancellationRequestId: UUID? = null

    @Column(name = "cancellation_approved")
    var cancellationApproved: Boolean? = null

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}
