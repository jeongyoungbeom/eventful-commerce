package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.entity.ProductReadModel
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ProductReadModelRepository : JpaRepository<ProductReadModel, UUID>
