package com.eventfulcommerce.product.domain.entity

import com.eventfulcommerce.common.BaseTimeEntity
import com.eventfulcommerce.common.SalesType
import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductLabel
import com.eventfulcommerce.product.domain.ProductStatus
import jakarta.persistence.*
import jakarta.persistence.OrderColumn
import java.util.UUID
import java.time.Instant

@Entity
@Table(name = "products")
class Product(
    @Column(nullable = false, updatable = false)
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
    var status: ProductStatus = ProductStatus.ACTIVE,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val salesType: SalesType = SalesType.MARKETPLACE
) : BaseTimeEntity() {
    init {
        salesType.validateSeller(sellerId)
        require(salesType == SalesType.MARKETPLACE || stock == 0) { "직매입 재고는 재고 서비스에서 관리합니다" }
    }


    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @Column(nullable = false)
    var informationVersion: Long = 0

    @Column(name = "inventory_snapshot_at")
    var inventorySnapshotAt: Instant? = null

    @Column(name = "last_inventory_adjustment_at")
    var lastInventoryAdjustmentAt: Instant? = null

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
        this.informationVersion = Math.incrementExact(informationVersion)
        this.name = name
        this.description = description
        this.price = price
        this.category = category
        this.labels = labels.toMutableSet()
        if (imageUrls != null) this.imageUrls = imageUrls.toMutableList()
    }

    fun adjustStock(delta: Int) {
        check(salesType == SalesType.MARKETPLACE) { "직매입 재고는 재고 서비스에서 관리합니다" }
        require(stock + delta >= 0) { "재고는 0 미만이 될 수 없습니다" }
        this.stock += delta
    }

    fun replaceStock(stock: Int, snapshotAt: Instant) {
        check(salesType == SalesType.MARKETPLACE) { "직매입 재고는 재고 서비스에서 관리합니다" }
        require(stock >= 0) { "재고는 0 미만이 될 수 없습니다" }
        this.stock = stock
        this.inventorySnapshotAt = snapshotAt
    }

    fun deactivate() {
        this.status = ProductStatus.INACTIVE
    }
}
