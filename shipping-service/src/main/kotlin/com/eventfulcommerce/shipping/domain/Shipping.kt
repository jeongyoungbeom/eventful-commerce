package com.eventfulcommerce.shipping.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "shipping")
class Shipping(
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

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

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}