package com.eventfulcommerce.shipping.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "shipping")
class Shipping(
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ShippingStatus = ShippingStatus.SHIPPING_READY,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
    ) {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}