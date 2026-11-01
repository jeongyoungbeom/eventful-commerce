package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.entity.ProductReadModel
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ProductReadModelRepository : JpaRepository<ProductReadModel, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from ProductReadModel product where product.productId = :productId")
    fun findByIdForUpdate(@Param("productId") productId: UUID): ProductReadModel?

    @Query("select product.productId from ProductReadModel product")
    fun findAllProductIds(): List<UUID>
}
