package com.eventfulcommerce.inventory.dto

import com.eventfulcommerce.inventory.domain.*
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.util.UUID

data class LotQuantity(val lotId: UUID, @param:InventoryInteger @field:Positive val quantity: Long)
data class OutboundActionRequest(
    @field:NotBlank @field:Size(max = 500) val reason: String,
    @field:NotEmpty @field:Size(max = 1000) @field:Valid val lines: List<LotQuantity>
)
data class CancellationDecisionRequest(val approved: Boolean, @field:NotBlank @field:Size(max = 500) val reason: String)
data class ReturnRequest(val outboundId: UUID, @field:NotBlank @field:Size(max = 500) val reason: String)
data class ReturnLotResult(val lotId: UUID, @param:InventoryInteger @field:PositiveOrZero val good: Long, @param:InventoryInteger @field:PositiveOrZero val damaged: Long)
data class ReturnInspectionRequest(
    @field:NotBlank @field:Size(max = 500) val reason: String,
    @field:NotEmpty @field:Size(max = 1000) @field:Valid val lines: List<ReturnLotResult>
)
data class OutboundResponse(val outbound: OutboundOrder, val lines: List<ReservationLine>)
