package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.entity.ProductReadModel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ProductReadModelRepository : JpaRepository<ProductReadModel, UUID> {
    @Query("select product.productId from ProductReadModel product")
    fun findAllProductIds(): List<UUID>
}
