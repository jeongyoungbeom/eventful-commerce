package com.eventfulcommerce.inventory.repository

import com.eventfulcommerce.inventory.domain.*
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

interface WarehouseRepository : JpaRepository<Warehouse, UUID>
interface SupplierRepository : JpaRepository<Supplier, UUID>
interface InventorySkuRepository : JpaRepository<InventorySku, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InventorySku s where s.id = :id")
    fun lock(id: UUID): InventorySku?

    @Modifying
    @Query(value = "INSERT INTO inventory_sku (id, name, active, information_version) VALUES (:id, :name, true, 0) ON CONFLICT (id) DO NOTHING", nativeQuery = true)
    fun insertIfAbsent(id: UUID, name: String): Int
}
interface PurchaseOrderRepository : JpaRepository<PurchaseOrder, UUID> {
    @Query("select p from PurchaseOrder p where (:warehouseId is null or p.warehouseId = :warehouseId) order by p.createdAt desc, p.id")
    fun operations(warehouseId: UUID?, pageable: Pageable): Page<PurchaseOrder>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = :id")
    fun lock(id: UUID): PurchaseOrder?

    @Modifying
    @Query(value = """INSERT INTO inventory_purchase_order (id, supplier_id, warehouse_id, actor_id, request_content, created_at)
        VALUES (:id, :supplierId, :warehouseId, :actorId, :content, :now) ON CONFLICT (id) DO NOTHING""", nativeQuery = true)
    fun insertIfAbsent(id: UUID, supplierId: UUID, warehouseId: UUID, actorId: UUID, content: String, now: Instant): Int
}
interface PurchaseLineRepository : JpaRepository<PurchaseLine, UUID> {
    fun findByPurchaseOrderIdOrderBySkuId(purchaseOrderId: UUID): List<PurchaseLine>
}
interface InventoryLotRepository : JpaRepository<InventoryLot, UUID> {
    fun findBySkuIdAndSupplierIdAndCodeAndQuality(skuId: UUID, supplierId: UUID, code: String, quality: LotQuality): InventoryLot?
}
interface StockSummaryRow {
    val warehouseId: UUID
    val onHand: BigDecimal
    val reserved: BigDecimal
    val allocated: BigDecimal
    val blocked: BigDecimal
    val transferHeld: BigDecimal
    val available: BigDecimal
}
interface StockBalanceRepository : JpaRepository<StockBalance, UUID> {
    @Query(value = """SELECT b.* FROM inventory_stock_balance b
        JOIN inventory_warehouse w ON w.id = b.warehouse_id JOIN inventory_lot l ON l.id = b.lot_id
        WHERE b.sku_id = :skuId AND w.active AND w.accepts_reservations AND l.quality = 'GOOD'
          AND l.expires_on > :today AND b.on_hand - b.reserved - b.allocated - b.blocked - b.transfer_held > 0
        ORDER BY w.priority, w.id, l.expires_on, l.id FOR UPDATE OF b""", nativeQuery = true)
    fun candidates(skuId: UUID, today: LocalDate): List<StockBalance>

    @Query(value = """SELECT b.warehouse_id AS "warehouseId", sum(b.on_hand) AS "onHand",
        sum(b.reserved) AS reserved, sum(b.allocated) AS allocated, sum(b.blocked) AS blocked, sum(b.transfer_held) AS "transferHeld",
        sum(CASE WHEN s.active AND w.active AND w.accepts_reservations AND l.quality = 'GOOD' AND l.expires_on > :today
            THEN b.on_hand - b.reserved - b.allocated - b.blocked - b.transfer_held ELSE 0 END) AS available
        FROM inventory_stock_balance b JOIN inventory_lot l ON l.id = b.lot_id
        JOIN inventory_warehouse w ON w.id = b.warehouse_id JOIN inventory_sku s ON s.id = b.sku_id
        WHERE b.sku_id = :skuId AND (CAST(:warehouseId AS uuid) IS NULL OR b.warehouse_id = :warehouseId)
        GROUP BY b.warehouse_id ORDER BY b.warehouse_id""", nativeQuery = true)
    fun summary(skuId: UUID, warehouseId: UUID?, today: LocalDate): List<StockSummaryRow>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByWarehouseIdAndLotId(warehouseId: UUID, lotId: UUID): StockBalance?

    @Query("select b from StockBalance b where b.skuId = :skuId and (:warehouseId is null or b.warehouseId = :warehouseId) order by b.warehouseId, b.lotId")
    fun stock(skuId: UUID, warehouseId: UUID?, pageable: Pageable): Page<StockBalance>
}
interface InboundReceiptRepository : JpaRepository<InboundReceipt, UUID> {
    @Query("select r from InboundReceipt r where (:warehouseId is null or r.purchaseOrderId in (select p.id from PurchaseOrder p where p.warehouseId = :warehouseId)) order by r.confirmedAt desc, r.id")
    fun operations(warehouseId: UUID?, pageable: Pageable): Page<InboundReceipt>

    @Modifying
    @Query(value = """INSERT INTO inventory_receipt (id, purchase_order_id, actor_id, reason, request_content, confirmed_at)
        VALUES (:id, :orderId, :actorId, :reason, :content, :now) ON CONFLICT (id) DO NOTHING""", nativeQuery = true)
    fun insertIfAbsent(id: UUID, orderId: UUID, actorId: UUID, reason: String, content: String, now: Instant): Int
}
interface ReceiptLineRepository : JpaRepository<ReceiptLine, UUID> {
    fun findByReceiptIdOrderById(receiptId: UUID): List<ReceiptLine>
}
interface StockLedgerRepository : JpaRepository<StockLedger, UUID> {
    fun findBySkuIdOrderByOccurredAtAscIdAsc(skuId: UUID, pageable: Pageable): Page<StockLedger>
}

interface InspectionRepository : JpaRepository<InspectionDecision, UUID> {
    @Modifying
    @Query(value = """INSERT INTO inventory_inspection (id, receipt_line_id, good_quantity, damaged_quantity,
        actor_id, reason, request_content, inspected_at) VALUES (:id, :lineId, :good, :damaged, :actorId, :reason, :content, :now)
        ON CONFLICT DO NOTHING""", nativeQuery = true)
    fun insertIfAbsent(id: UUID, lineId: UUID, good: Long, damaged: Long, actorId: UUID, reason: String, content: String, now: Instant): Int
}
