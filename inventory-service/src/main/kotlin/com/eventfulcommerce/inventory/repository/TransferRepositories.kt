package com.eventfulcommerce.inventory.repository

import com.eventfulcommerce.inventory.domain.*
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.*
import java.time.Instant
import java.util.UUID

interface TransferRepository : JpaRepository<TransferOrder, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransferOrder t where t.id = :id")
    fun lock(id: UUID): TransferOrder?

    @Modifying
    @Query(value = """INSERT INTO inventory_transfer (id, source_warehouse_id, destination_warehouse_id,
        expected_arrival_at, actor_id, reason, request_content, status, created_at)
        VALUES (:id, :source, :destination, :expected, :actor, :reason, :content, 'HELD', :now)
        ON CONFLICT (id) DO NOTHING""", nativeQuery = true)
    fun insertIfAbsent(id: UUID, source: UUID, destination: UUID, expected: Instant, actor: UUID,
        reason: String, content: String, now: Instant): Int

    @Query("""select t from TransferOrder t where
        (:warehouseId is null or t.sourceWarehouseId = :warehouseId or t.destinationWarehouseId = :warehouseId)
        and (:incompleteOnly = false or t.status in ('HELD','IN_TRANSIT','PARTIALLY_RECEIVED'))
        and (:overdueOnly = false or (t.status in ('HELD','IN_TRANSIT','PARTIALLY_RECEIVED') and t.expectedArrivalAt <= :now))
        order by t.createdAt, t.id""")
    fun search(warehouseId: UUID?, incompleteOnly: Boolean, overdueOnly: Boolean, now: Instant, pageable: Pageable): Page<TransferOrder>
}
interface TransferLineRepository : JpaRepository<TransferLine, UUID> {
    fun findByTransferIdOrderByLotId(transferId: UUID): List<TransferLine>
}
interface TransferReceiptRepository : JpaRepository<TransferReceipt, UUID> {
    @Modifying
    @Query(value = """INSERT INTO inventory_transfer_receipt (id, transfer_id, actor_id, reason, request_content, received_at)
        VALUES (:id, :transferId, :actor, :reason, :content, :now) ON CONFLICT (id) DO NOTHING""", nativeQuery = true)
    fun insertIfAbsent(id: UUID, transferId: UUID, actor: UUID, reason: String, content: String, now: Instant): Int
    fun findByTransferIdOrderByReceivedAtAscIdAsc(transferId: UUID, pageable: Pageable): Page<TransferReceipt>
}
interface TransferReceiptLineRepository : JpaRepository<TransferReceiptLine, UUID> {
    fun findByReceiptIdOrderByLotId(receiptId: UUID): List<TransferReceiptLine>
}
