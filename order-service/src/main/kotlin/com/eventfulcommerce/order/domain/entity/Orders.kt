package com.eventfulcommerce.order.domain.entity

import com.eventfulcommerce.common.BaseTimeEntity
import com.eventfulcommerce.order.domain.OrdersStatus
import jakarta.persistence.*
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
    val status: OrdersStatus,
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}