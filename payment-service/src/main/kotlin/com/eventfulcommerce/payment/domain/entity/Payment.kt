package com.eventfulcommerce.payment.domain.entity

import com.eventfulcommerce.payment.domain.PaymentStatus
import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "payment")
class Payment(
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentStatus,

    @Column(nullable = false)
    val amount: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}