package com.eventfulcommerce.order.domain.entity

import com.eventfulcommerce.common.BaseTimeEntity
import com.eventfulcommerce.order.domain.OrdersStatus
import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "orders")
class Orders(
    @Column(nullable = false)
    val userId: UUID,

    @Column(nullable = false)
    val totalAmount: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrdersStatus,

    var reservationId: UUID? = null,
    var expiresAt: Instant? = null,

    @Version
    var version: Long = 0L

) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}