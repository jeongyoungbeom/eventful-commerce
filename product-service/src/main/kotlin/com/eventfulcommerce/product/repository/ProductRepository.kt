package com.eventfulcommerce.product.repository

import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductLabel
import com.eventfulcommerce.product.domain.ProductStatus
import com.eventfulcommerce.product.domain.entity.Product
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ProductRepository : JpaRepository<Product, UUID> {
    fun findByStatusAndCategory(status: ProductStatus, category: ProductCategory): List<Product>
    fun findByStatus(status: ProductStatus): List<Product>
    fun findBySellerIdAndStatus(sellerId: UUID, status: ProductStatus): List<Product>

    @Query("SELECT DISTINCT p FROM Product p WHERE p.status = :status AND :label MEMBER OF p.labels")
    fun findByStatusAndLabel(@Param("status") status: ProductStatus, @Param("label") label: ProductLabel): List<Product>

    @Query("SELECT DISTINCT p FROM Product p WHERE p.status = :status AND p.category = :category AND :label MEMBER OF p.labels")
    fun findByStatusAndCategoryAndLabel(@Param("status") status: ProductStatus, @Param("category") category: ProductCategory, @Param("label") label: ProductLabel): List<Product>
}
