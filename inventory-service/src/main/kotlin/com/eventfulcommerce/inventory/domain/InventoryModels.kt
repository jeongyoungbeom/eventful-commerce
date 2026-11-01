package com.eventfulcommerce.inventory.domain

import jakarta.persistence.*
import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class LotQuality { GOOD, DAMAGED, INSPECTION }

@Entity
@Table(name = "inventory_warehouse")
class Warehouse(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true, length = 40) val code: String,
    @Column(nullable = false, length = 100) val name: String,
    @Column(nullable = false, length = 300) val address: String,
    @Column(nullable = false) val active: Boolean = true,
    @Column(nullable = false) val acceptsReservations: Boolean = true,
    @Column(nullable = false) val priority: Int = 0
)

@Entity
@Table(name = "inventory_supplier")
class Supplier(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true, length = 40) val code: String,
    @Column(nullable = false, length = 100) val name: String
)

@Entity
@Table(name = "inventory_sku")
class InventorySku(
    @Id val id: UUID,
    @Column(nullable = false, length = 255) var name: String,
    @Column(nullable = false) var active: Boolean = true,
    @Column(nullable = false) var informationVersion: Long = 0
)

@Entity
@Table(name = "inventory_purchase_order")
class PurchaseOrder(
    @Id val id: UUID,
    @Column(nullable = false) val supplierId: UUID,
    @Column(nullable = false) val warehouseId: UUID,
    @Column(nullable = false) val actorId: UUID,
    @get:JsonIgnore @Column(nullable = false, columnDefinition = "text") val requestContent: String,
    @Column(nullable = false) val createdAt: Instant
)

@Entity
@Table(name = "inventory_purchase_line")
class PurchaseLine(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val purchaseOrderId: UUID,
    @Column(nullable = false) val skuId: UUID,
    @Column(nullable = false) val orderedQuantity: Long,
    @Column(nullable = false) val unitCost: Long,
    @Column(nullable = false) var receivedQuantity: Long = 0
)

@Entity
@Table(name = "inventory_lot")
class InventoryLot(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val skuId: UUID,
    @Column(nullable = false) val supplierId: UUID,
    @Column(nullable = false, length = 80) val code: String,
    @Column(nullable = false) val expiresOn: LocalDate,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) val quality: LotQuality
)

@Entity
@Table(name = "inventory_stock_balance")
class StockBalance(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val skuId: UUID,
    @Column(nullable = false) val warehouseId: UUID,
    @Column(nullable = false) val lotId: UUID,
    @Column(nullable = false) var onHand: Long = 0,
    @Column(nullable = false) var reserved: Long = 0,
    @Column(nullable = false) var allocated: Long = 0,
    @Column(nullable = false) var blocked: Long = 0,
    @Column(nullable = false) var transferHeld: Long = 0,
    @Version var version: Long = 0
)

@Entity
@Table(name = "inventory_receipt")
class InboundReceipt(
    @Id val id: UUID,
    @Column(nullable = false) val purchaseOrderId: UUID,
    @Column(nullable = false) val actorId: UUID,
    @Column(nullable = false, length = 500) val reason: String,
    @get:JsonIgnore @Column(nullable = false, columnDefinition = "text") val requestContent: String,
    @Column(nullable = false) val confirmedAt: Instant
)

@Entity
@Table(name = "inventory_receipt_line")
class ReceiptLine(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val receiptId: UUID,
    @Column(nullable = false) val purchaseLineId: UUID,
    @Column(nullable = false) val lotId: UUID,
    @Column(nullable = false) val quantity: Long,
    @Column(nullable = false) val unitCost: Long,
    @Column(nullable = false) val purchaseAmount: Long
)

@Entity
@Table(name = "inventory_stock_ledger")
class StockLedger(
    @Id val id: UUID = UUID.randomUUID(),
    @Column val receiptId: UUID? = null,
    @Column val receiptLineId: UUID? = null,
    @Column(nullable = false, length = 20) val documentType: String = "INBOUND",
    @Column(nullable = false) val documentId: UUID = requireNotNull(receiptId),
    @Column(nullable = false) val skuId: UUID,
    @Column(nullable = false) val warehouseId: UUID,
    @Column(nullable = false) val lotId: UUID,
    @Column(nullable = false) val onHandDelta: Long,
    @Column(nullable = false) val blockedDelta: Long,
    @Column(nullable = false) val reservedDelta: Long = 0,
    @Column(nullable = false) val allocatedDelta: Long = 0,
    @Column(nullable = false) val transferHeldDelta: Long = 0,
    @Column val actorId: UUID? = null,
    @Column(nullable = false, length = 500) val reason: String,
    @Column(nullable = false) val occurredAt: Instant
)

@Entity
@Table(name = "inventory_inspection")
class InspectionDecision(
    @Id val id: UUID,
    @Column(nullable = false, unique = true) val receiptLineId: UUID,
    @Column(nullable = false) val goodQuantity: Long,
    @Column(nullable = false) val damagedQuantity: Long,
    @Column(nullable = false) val actorId: UUID,
    @Column(nullable = false, length = 500) val reason: String,
    @get:JsonIgnore @Column(nullable = false, columnDefinition = "text") val requestContent: String,
    @Column(nullable = false) val inspectedAt: Instant
)
