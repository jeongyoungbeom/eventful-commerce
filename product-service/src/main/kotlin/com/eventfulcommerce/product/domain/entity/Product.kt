package com.eventfulcommerce.product.domain.entity

import com.eventfulcommerce.common.BaseTimeEntity
import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductStatus
import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "products")
class Product(
    @Column(nullable = false)
    val sellerId: UUID,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    var description: String,

    @Column(nullable = false)
    var price: Long,

    @Column(nullable = false)
    var stock: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var category: ProductCategory,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ProductStatus = ProductStatus.ACTIVE
) : BaseTimeEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    fun update(name: String, description: String, price: Long, category: ProductCategory) {
        this.name = name
        this.description = description
        this.price = price
        this.category = category
    }

    fun adjustStock(delta: Int) {
        require(stock + delta >= 0) { "재고는 0 미만이 될 수 없습니다" }
        this.stock += delta
    }

    fun deactivate() {
        this.status = ProductStatus.INACTIVE
    }
}
