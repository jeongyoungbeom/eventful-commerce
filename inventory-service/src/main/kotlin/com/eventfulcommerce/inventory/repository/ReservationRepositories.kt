package com.eventfulcommerce.inventory.repository

import com.eventfulcommerce.inventory.domain.*
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.*
import java.time.Instant
import java.util.UUID

interface ReservationRepository : JpaRepository<Reservation, UUID> {
    @Query("""select r from Reservation r where (:warehouseId is null or r.warehouseId = :warehouseId)
        and (:skuId is null or r.skuId = :skuId) and r.status in ('PENDING','RESERVED','ALLOCATED') order by r.createdAt, r.id""")
    fun operations(warehouseId: UUID?, skuId: UUID?, pageable: Pageable): org.springframework.data.domain.Page<Reservation>

    @Modifying
    @Query(value = """INSERT INTO inventory_reservation
        (id, order_attempt_id, order_item_id, sku_id, quantity, expires_at, request_content, status, created_at, updated_at)
        VALUES (:id, :attemptId, :itemId, :skuId, :quantity, :expiresAt, :content, 'PENDING', :now, :now)
        ON CONFLICT (id) DO NOTHING""", nativeQuery = true)
    fun insertIfAbsent(id: UUID, attemptId: UUID, itemId: UUID, skuId: UUID, quantity: Long,
        expiresAt: Instant, content: String, now: Instant): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    fun lock(id: UUID): Reservation?

    @Query("select r.id from Reservation r where r.status = 'RESERVED' and r.expiresAt <= :now order by r.expiresAt, r.id")
    fun due(now: Instant, pageable: Pageable): List<UUID>
}

interface ReservationLineRepository : JpaRepository<ReservationLine, UUID> {
    fun findByReservationIdOrderByLotId(reservationId: UUID): List<ReservationLine>
}
