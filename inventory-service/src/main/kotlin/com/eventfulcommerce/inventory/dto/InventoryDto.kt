package com.eventfulcommerce.inventory.dto

import com.eventfulcommerce.inventory.domain.*
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.time.LocalDate
import java.util.UUID

data class WarehouseRequest(
    @field:NotBlank @field:Size(max = 40) val code: String,
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:NotBlank @field:Size(max = 300) val address: String,
    val active: Boolean = true,
    val acceptsReservations: Boolean = true,
    @field:Min(0) val priority: Int = 0
)
data class SupplierRequest(
    @field:NotBlank @field:Size(max = 40) val code: String,
    @field:NotBlank @field:Size(max = 100) val name: String
)
data class PurchaseOrderRequest(
    val supplierId: UUID,
    val warehouseId: UUID,
    @field:Valid @field:Size(min = 1, max = 100) val lines: List<PurchaseLineRequest>
)
data class PurchaseLineRequest(val skuId: UUID, @param:InventoryInteger @field:Positive val quantity: Long, @param:InventoryInteger @field:Positive val unitCost: Long)
data class InboundRequest(
    val purchaseOrderId: UUID,
    @field:NotBlank @field:Size(max = 500) val reason: String,
    @field:Valid @field:Size(min = 1, max = 100) val lines: List<InboundLineRequest>
)
data class InboundLineRequest(
    val purchaseLineId: UUID,
    @field:NotBlank @field:Size(max = 80) val lotCode: String,
    val expiresOn: LocalDate,
    val quality: LotQuality,
    @param:InventoryInteger @field:Positive val quantity: Long
)
data class PurchaseOrderResponse(val order: PurchaseOrder, val lines: List<PurchaseLine>, val status: String)
data class ReceiptLineResponse(val line: ReceiptLine, val lot: InventoryLot)
data class ReceiptResponse(val receipt: InboundReceipt, val lines: List<ReceiptLineResponse>, val purchaseAmount: Long)
data class StockResponse(val balance: StockBalance, val lot: InventoryLot, val available: Long)

data class InspectionRequest(
    val receiptLineId: UUID,
    @param:InventoryInteger @field:PositiveOrZero val goodQuantity: Long,
    @param:InventoryInteger @field:PositiveOrZero val damagedQuantity: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String
)

data class StockSummary(val skuId: UUID, val warehouseId: UUID, val onHand: Long, val reserved: Long,
    val allocated: Long, val blocked: Long, val available: Long, val transferHeld: Long)
