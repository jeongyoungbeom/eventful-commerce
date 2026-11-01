package com.eventfulcommerce.inventory.repository

import com.eventfulcommerce.inventory.domain.*
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID
import java.time.Instant
import org.springframework.data.jpa.repository.Modifying

interface OutboundRepository : JpaRepository<OutboundOrder, UUID> {
    @Query("""select o from OutboundOrder o where (:warehouseId is null or o.warehouseId = :warehouseId)
        and (o.status in ('READY','PICKING','PACKED') or (o.status = 'SHIPPED' and o.deliveredAt is null)) order by o.createdAt, o.id""")
    fun operations(warehouseId: UUID?, pageable: Pageable): Page<OutboundOrder>

    fun findByOrderIdOrderById(orderId: UUID, pageable: Pageable): Page<OutboundOrder>
}
interface ReturnReceiptRepository : JpaRepository<ReturnReceipt, UUID> {
    @Modifying
    @Query(value = """INSERT INTO inventory_return_receipt
        (id, outbound_id, reason, actor_id, status, requested_at)
        VALUES (:id, :outboundId, :reason, :actorId, 'REQUESTED', :now) ON CONFLICT DO NOTHING""", nativeQuery = true)
    fun insertIfAbsent(id: UUID, outboundId: UUID, reason: String, actorId: UUID, now: Instant): Int
    fun findByOutboundId(outboundId: UUID): ReturnReceipt?
    @Query("select r.outboundId from ReturnReceipt r where r.id = :id")
    fun outboundId(id: UUID): UUID?
}
