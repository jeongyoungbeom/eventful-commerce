package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.entity.SellerOrder
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SellerOrderRepository : JpaRepository<SellerOrder, UUID> {
    fun findBySellerIdOrderByCreatedAtDesc(sellerId: UUID): List<SellerOrder>
}
