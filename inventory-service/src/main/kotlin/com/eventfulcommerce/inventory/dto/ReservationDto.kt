package com.eventfulcommerce.inventory.dto

import com.eventfulcommerce.inventory.domain.Reservation
import com.eventfulcommerce.inventory.domain.ReservationLine
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID

data class ReservationRequest(
    val orderAttemptId: UUID,
    val orderItemId: UUID,
    val skuId: UUID,
    @param:InventoryInteger @field:Positive val quantity: Long,
    val expiresAt: Instant
)

data class ReservationResponse(val reservation: Reservation, val lines: List<ReservationLine>, val cancellationStatus: String? = null)
