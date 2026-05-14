package com.eventfulcommerce.order.domain.entity

import com.eventfulcommerce.common.BaseTimeEntity
import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "seller_orders")
class SellerOrder(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    val order: Orders,

    @Column(nullable = false)
    val sellerId: UUID,

    @Column(nullable = false)
    var itemTotalAmount: Long,

    @Column(nullable = false)
    var deliveryFee: Long = 0,

    @Column(nullable = false)
    var paymentAmount: Long,

    @Column(nullable = false)
    var commissionRate: Double,

    @Column(nullable = false)
    var commissionAmount: Long,

    @Column(nullable = false)
    var settlementAmount: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SellerOrderStatus = SellerOrderStatus.RESERVED,

    @OneToMany(mappedBy = "sellerOrder", cascade = [CascadeType.ALL], orphanRemoval = true)
    val items: MutableList<OrderItem> = mutableListOf()
) : BaseTimeEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    fun addItem(item: OrderItem) {
        items.add(item)
    }

    fun cancel() {
        status = SellerOrderStatus.CANCELED
        items.forEach { it.status = OrderItemStatus.CANCELED }
    }

    fun confirm() {
        status = SellerOrderStatus.CONFIRMED
        items.forEach { it.status = OrderItemStatus.CONFIRMED }
    }
}

