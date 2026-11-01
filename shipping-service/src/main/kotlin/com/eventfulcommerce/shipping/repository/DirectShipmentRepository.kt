package com.eventfulcommerce.shipping.repository

import com.eventfulcommerce.shipping.domain.DirectShipment
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.*
import java.time.Instant
import java.util.UUID

interface DirectShipmentRepository : JpaRepository<DirectShipment, UUID> {
    @Modifying
    @Query(value = """INSERT INTO direct_shipment (id, order_id, seller_order_id, warehouse_id, request_content, status, shipped_at)
        VALUES (:id, :orderId, :sellerOrderId, :warehouseId, :content, 'STARTED', :now) ON CONFLICT (id) DO NOTHING""", nativeQuery = true)
    fun insertIfAbsent(id: UUID, orderId: UUID, sellerOrderId: UUID, warehouseId: UUID, content: String, now: Instant): Int
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DirectShipment s where s.id = :id")
    fun lock(id: UUID): DirectShipment?
    @Query("select s.id from DirectShipment s where s.status = 'STARTED' and s.shippedAt <= :cutoff order by s.shippedAt, s.id")
    fun due(cutoff: Instant, pageable: Pageable): List<UUID>
}
