package com.eventfulcommerce.inventory.dto

import com.eventfulcommerce.inventory.domain.*
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.time.Instant
import java.util.UUID

data class TransferRequest(
    val sourceWarehouseId: UUID,
    val destinationWarehouseId: UUID,
    val expectedArrivalAt: Instant,
    @field:NotBlank @field:Size(max = 500) val reason: String,
    @field:Valid @field:Size(min = 1, max = 100) val lines: List<TransferQuantity>
)
data class TransferQuantity(val lotId: UUID, @param:InventoryInteger @field:Positive val quantity: Long)
data class TransferActionRequest(@field:NotBlank @field:Size(max = 500) val reason: String)
data class TransferArrivalRequest(
    @field:NotBlank @field:Size(max = 500) val reason: String,
    @field:Valid @field:Size(min = 1, max = 100) val lines: List<TransferQuantity>
)
data class TransferLineResponse(val line: TransferLine, val lot: InventoryLot, val heldQuantity: Long, val inTransitQuantity: Long)
data class TransferResponse(val transfer: TransferOrder, val lines: List<TransferLineResponse>)
data class TransferReceiptResponse(val receipt: TransferReceipt, val lines: List<TransferReceiptLine>)
