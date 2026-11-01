package com.eventfulcommerce.inventory.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class ReservationStatus { PENDING, RESERVED, REJECTED, RELEASED, EXPIRED, ALLOCATED, INVALIDATED, CANCELED, SHIPPED, RETURNED }

@Entity
@Table(name = "inventory_reservation")
class Reservation(
    @Id val id: UUID,
    @Column(nullable = false) val orderAttemptId: UUID,
    @Column(nullable = false) val orderItemId: UUID,
    @Column(nullable = false) val skuId: UUID,
    @Column(nullable = false) val quantity: Long,
    @Column(nullable = false) val expiresAt: Instant,
    @get:JsonIgnore @Column(nullable = false, columnDefinition = "text") val requestContent: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: ReservationStatus,
    @Column var warehouseId: UUID? = null,
    @Column(length = 50) var reason: String? = null,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) var updatedAt: Instant
)

@Entity
@Table(name = "inventory_reservation_line")
class ReservationLine(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val reservationId: UUID,
    @Column(nullable = false) val skuId: UUID,
    @Column(nullable = false) val warehouseId: UUID,
    @Column(nullable = false) val lotId: UUID,
    @Column(nullable = false) val quantity: Long
)
