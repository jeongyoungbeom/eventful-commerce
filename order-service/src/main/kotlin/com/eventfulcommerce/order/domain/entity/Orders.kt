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
    var totalItemAmount: Long = 0,

    @Column(nullable = false)
    var totalDeliveryFee: Long = 0,

    @Column(nullable = false)
    var totalPaymentAmount: Long = 0,

    @Column(nullable = false)
    var totalCommissionAmount: Long = 0,

    @Column(nullable = false)
    var totalSettlementAmount: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrdersStatus,

    var expiresAt: Instant? = null,

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    val sellerOrders: MutableList<SellerOrder> = mutableListOf(),

    @Version
    var version: Long = 0L

) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    fun addSellerOrder(sellerOrder: SellerOrder) {
        sellerOrders.add(sellerOrder)
    }

    fun recomputeTotals() {
        totalItemAmount = sellerOrders.sumOf { it.itemTotalAmount }
        totalDeliveryFee = sellerOrders.sumOf { it.deliveryFee }
        totalPaymentAmount = sellerOrders.sumOf { it.paymentAmount }
        totalCommissionAmount = sellerOrders.sumOf { it.commissionAmount }
        totalSettlementAmount = sellerOrders.sumOf { it.settlementAmount }
    }

    fun recomputeStatus() {
        status = when {
            sellerOrders.isEmpty() -> OrdersStatus.ORDER_FAILED
            sellerOrders.all { it.status == SellerOrderStatus.CANCELED } -> OrdersStatus.ORDER_CANCELED
            sellerOrders.any { it.status == SellerOrderStatus.CANCELED } -> OrdersStatus.ORDER_PARTIALLY_CANCELED
            sellerOrders.all { it.status == SellerOrderStatus.CONFIRMED } -> OrdersStatus.ORDER_CONFIRMED
            else -> OrdersStatus.ORDER_RESERVED
        }
    }
}
