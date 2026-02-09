package com.eventfulcommerce.shipping.repository

import com.eventfulcommerce.shipping.domain.Shipping
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ShippingRepository : JpaRepository<Shipping, UUID> {
    fun existsByOrderId(id: UUID): Boolean
}