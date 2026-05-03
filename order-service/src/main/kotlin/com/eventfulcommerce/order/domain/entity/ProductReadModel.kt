package com.eventfulcommerce.order.domain.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "product_read_model")
class ProductReadModel(
    @Id
    val productId: UUID,

    @Column(nullable = false)
    val sellerId: UUID,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var price: Long,

    @Column(nullable = false)
    var stock: Int,

    @Column(nullable = false)
    var category: String,

    @Column(nullable = false)
    var status: String = "ACTIVE",

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    fun updateStock(delta: Int, newStock: Int) {
        this.stock = newStock
        this.updatedAt = Instant.now()
    }

    fun deactivate() {
        this.status = "INACTIVE"
        this.updatedAt = Instant.now()
    }
}
