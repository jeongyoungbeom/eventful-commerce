package com.eventfulcommerce.inventory.repository

import com.eventfulcommerce.inventory.domain.*
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.*
import java.time.Instant
import java.util.UUID

interface StockCountRepository : JpaRepository<StockCount, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StockCount c where c.id = :id")
    fun lock(id: UUID): StockCount?

    @Modifying
    @Query(value = """INSERT INTO inventory_stock_count (id,sku_id,warehouse_id,lot_id,base_on_hand,base_blocked,
        base_version,started_at,started_by,reason,reconciliation_id,request_content,status)
        VALUES (:id,:skuId,:warehouseId,:lotId,:onHand,:blocked,:version,:now,:actor,:reason,:reportId,:content,'OPEN')
        ON CONFLICT (id) DO NOTHING""", nativeQuery = true)
    fun insertIfAbsent(id: UUID, skuId: UUID, warehouseId: UUID, lotId: UUID, onHand: Long, blocked: Long,
        version: Long, now: Instant, actor: UUID, reason: String, reportId: UUID?, content: String): Int

    @Query("""select c from StockCount c where (:warehouseId is null or c.warehouseId = :warehouseId)
        and (:incompleteOnly = false or c.status in ('OPEN','MEASURED')) order by c.startedAt, c.id""")
    fun search(warehouseId: UUID?, incompleteOnly: Boolean, pageable: Pageable): Page<StockCount>
}

interface ReconciliationRepository : JpaRepository<StockReconciliation, UUID> {
    @Modifying
    @Query(value = """INSERT INTO inventory_reconciliation (id,sku_id,warehouse_id,previous_report_id,checked_at,actor_id,
        reason,page_number,page_size,has_next,mismatch_count,request_content,result_content)
        VALUES (:id,:skuId,:warehouseId,:previousId,:now,:actor,:reason,:page,:size,:hasNext,:mismatches,:content,:result)
        ON CONFLICT (id) DO NOTHING""", nativeQuery = true)
    fun insertIfAbsent(id: UUID, skuId: UUID, warehouseId: UUID?, previousId: UUID?, now: Instant, actor: UUID,
        reason: String, page: Int, size: Int, hasNext: Boolean, mismatches: Int, content: String, result: String): Int

    @Query("""select r from StockReconciliation r where r.skuId = :skuId
        and (:warehouseId is null or r.warehouseId = :warehouseId) order by r.checkedAt desc, r.id""")
    fun search(skuId: UUID, warehouseId: UUID?, pageable: Pageable): Page<StockReconciliation>
}
