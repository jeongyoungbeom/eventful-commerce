package com.eventfulcommerce.product.domain.entity

import com.eventfulcommerce.common.BaseTimeEntity
import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductLabel
import com.eventfulcommerce.product.domain.ProductStatus
import jakarta.persistence.*
import jakarta.persistence.OrderColumn
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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_labels", joinColumns = [JoinColumn(name = "product_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "label")
    var labels: MutableSet<ProductLabel> = mutableSetOf()

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_images", joinColumns = [JoinColumn(name = "product_id")])
    @OrderColumn(name = "sort_order")
    @Column(name = "image_url")
    var imageUrls: MutableList<String> = mutableListOf()

    fun update(name: String, description: String, price: Long, category: ProductCategory, labels: Set<ProductLabel>, imageUrls: List<String>?) {
        this.name = name
        this.description = description
        this.price = price
        this.category = category
        this.labels = labels.toMutableSet()
        if (imageUrls != null) this.imageUrls = imageUrls.toMutableList()
    }

    fun adjustStock(delta: Int) {
        require(stock + delta >= 0) { "재고는 0 미만이 될 수 없습니다" }
        this.stock += delta
    }

    fun deactivate() {
        this.status = ProductStatus.INACTIVE
    }
}
