package com.eventfulcommerce.order.domain.entity

import com.eventfulcommerce.common.BaseTimeEntity
import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "order_items")
class OrderItem(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_order_id", nullable = false)
    val sellerOrder: SellerOrder,

    @Column(nullable = false)
    val productId: UUID,

    @Column(nullable = false)
    val productName: String,

    @Column(nullable = false)
    val quantity: Int,

    @Column(nullable = false)
    val unitPrice: Long,

    @Column(nullable = false)
    val totalAmount: Long,

    @Column(nullable = false)
    val reservationId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderItemStatus = OrderItemStatus.RESERVED
) : BaseTimeEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}

