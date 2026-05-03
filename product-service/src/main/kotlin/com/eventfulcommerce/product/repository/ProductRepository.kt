package com.eventfulcommerce.product.repository

import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductStatus
import com.eventfulcommerce.product.domain.entity.Product
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductRepository : JpaRepository<Product, UUID> {
    fun findByStatusAndCategory(status: ProductStatus, category: ProductCategory): List<Product>
    fun findByStatus(status: ProductStatus): List<Product>
    fun findBySellerIdAndStatus(sellerId: UUID, status: ProductStatus): List<Product>
}
