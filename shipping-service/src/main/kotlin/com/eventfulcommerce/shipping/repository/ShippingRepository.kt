package com.eventfulcommerce.shipping.repository

import com.eventfulcommerce.shipping.domain.Shipping
import com.eventfulcommerce.shipping.domain.ShippingStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID

interface ShippingRepository : JpaRepository<Shipping, UUID> {
    fun existsByOrderId(id: UUID): Boolean
    fun existsBySellerOrderId(id: UUID): Boolean
    fun findByStatusAndShippedAtBefore(status: ShippingStatus, cutoff: Instant, pageable: Pageable): List<Shipping>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select shipping from Shipping shipping where shipping.id = :shippingId")
    fun findByIdForUpdate(@Param("shippingId") shippingId: UUID): Shipping?
}
