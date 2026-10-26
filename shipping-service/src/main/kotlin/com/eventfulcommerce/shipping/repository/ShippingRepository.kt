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
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """insert into shipping (id, order_id, seller_order_id, user_id, status, created_at)
        values (:id, :orderId, :sellerOrderId, :userId, :status, :createdAt)
        on conflict (seller_order_id) do nothing""", nativeQuery = true)
    fun insertIfAbsent(@Param("id") id: UUID, @Param("orderId") orderId: UUID,
        @Param("sellerOrderId") sellerOrderId: UUID, @Param("userId") userId: UUID,
        @Param("status") status: String, @Param("createdAt") createdAt: Instant): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipping s where s.sellerOrderId = :sellerOrderId")
    fun findBySellerOrderIdForUpdate(@Param("sellerOrderId") sellerOrderId: UUID): Shipping?

    fun findByStatusAndCreatedAtBeforeOrderByCreatedAtAscIdAsc(status: ShippingStatus, cutoff: Instant, pageable: Pageable): List<Shipping>

    fun existsByOrderId(id: UUID): Boolean
    fun existsBySellerOrderId(id: UUID): Boolean
    fun findByStatusAndShippedAtBefore(status: ShippingStatus, cutoff: Instant, pageable: Pageable): List<Shipping>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select shipping from Shipping shipping where shipping.id = :shippingId")
    fun findByIdForUpdate(@Param("shippingId") shippingId: UUID): Shipping?
}
