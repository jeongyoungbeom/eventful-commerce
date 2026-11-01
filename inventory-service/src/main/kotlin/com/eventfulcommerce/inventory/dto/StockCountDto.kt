package com.eventfulcommerce.inventory.dto

import com.eventfulcommerce.inventory.domain.StockReconciliation
import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.*
import java.util.UUID

data class StockCountRequest(val warehouseId: UUID, val lotId: UUID,
    @field:NotBlank @field:Size(max = 500) val reason: String, val reconciliationId: UUID? = null)
data class StockCountMeasurement(
    @param:InventoryInteger
    @field:PositiveOrZero val countedQuantity: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String)
data class StockCountAction(@field:NotBlank @field:Size(max = 500) val reason: String)
data class ReconciliationRequest(val skuId: UUID, val warehouseId: UUID? = null,
    @field:NotBlank @field:Size(max = 500) val reason: String,
    @field:Min(0) val page: Int = 0, @field:Min(1) @field:Max(200) val size: Int = 50,
    val previousReportId: UUID? = null)
data class ReconciliationResponse(val report: StockReconciliation, val rows: JsonNode)
