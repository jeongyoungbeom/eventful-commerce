package com.eventfulcommerce.shipping.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "direct_shipment")
class DirectShipment(
    @Id val id: UUID,
    @Column(nullable = false) val orderId: UUID,
    @Column(nullable = false) val sellerOrderId: UUID,
    @Column(nullable = false) val warehouseId: UUID,
    @get:JsonIgnore @Column(nullable = false, columnDefinition = "text") val requestContent: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: ShippingStatus = ShippingStatus.STARTED,
    @Column(nullable = false) val shippedAt: Instant,
    @Column var completedAt: Instant? = null
)
