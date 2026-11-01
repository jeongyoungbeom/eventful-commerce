package com.eventfulcommerce.inventory.service

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.inventory.domain.*
import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.repository.*
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Validated
@Transactional(rollbackFor = [Exception::class])
class ReservationService(
    private val reservations: ReservationRepository,
    private val outbounds: OutboundRepository,
    private val lines: ReservationLineRepository,
    private val skus: InventorySkuRepository,
    private val warehouses: WarehouseRepository,
    private val lots: InventoryLotRepository,
    private val balances: StockBalanceRepository,
    private val ledger: StockLedgerRepository,
    private val outbox: OutboxEventService,
    private val mapper: ObjectMapper,
    private val clock: Clock
) {
    @PreAuthorize("hasAuthority('INVENTORY_SERVICE')")
    fun reserve(id: UUID, @Valid request: ReservationRequest): ReservationResponse {
        val reservation = initialize(id, request)
        if (reservation.status != ReservationStatus.PENDING) return current(reservation)
        require(reservation.expiresAt <= clock.instant().plus(Duration.ofMinutes(15))) { "예약 유효시간 상한은 15분입니다" }
        val sku = skus.lock(reservation.skuId)
        if (reservation.expiresAt <= clock.instant()) return finish(reservation, ReservationStatus.EXPIRED, "TTL_EXPIRED")
        if (sku?.active != true) return finish(reservation, ReservationStatus.REJECTED, "SKU_UNAVAILABLE")
        val candidates = balances.candidates(reservation.skuId, LocalDate.now(clock))
        for ((warehouseId, group) in candidates.groupBy { it.warehouseId }) {
            var remaining = reservation.quantity
            val chosen = mutableListOf<Pair<StockBalance, Long>>()
            for (balance in group) {
                val quantity = minOf(remaining, balance.onHand - balance.reserved - balance.allocated - balance.blocked - balance.transferHeld)
                chosen.add(balance to quantity)
                remaining -= quantity
                if (remaining == 0L) break
            }
            if (remaining != 0L) continue
            reservation.warehouseId = warehouseId
            // 확보 기록의 복합 FK를 넣기 전에 선택한 창고를 먼저 저장한다.
            reservations.flush()
            for ((balance, quantity) in chosen) {
                balance.reserved = Math.addExact(balance.reserved, quantity)
                lines.save(ReservationLine(reservationId = id, skuId = reservation.skuId,
                    warehouseId = warehouseId, lotId = balance.lotId, quantity = quantity))
                movement(reservation, balance, quantity, 0, "RESERVATION")
            }
            return finish(reservation, ReservationStatus.RESERVED, null)
        }
        return finish(reservation, ReservationStatus.REJECTED, "NO_SINGLE_WAREHOUSE_STOCK")
    }

    @PreAuthorize("hasAuthority('INVENTORY_SERVICE')")
    fun get(id: UUID): ReservationResponse = current(locked(id))

    @PreAuthorize("hasAuthority('INVENTORY_SERVICE')")
    fun release(id: UUID, @Valid request: ReservationRequest): ReservationResponse {
        val reservation = initialize(id, request)
        if (reservation.status in setOf(ReservationStatus.ALLOCATED, ReservationStatus.SHIPPED, ReservationStatus.RETURNED)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "할당 재고는 창고 취소 절차가 필요합니다")
        }
        if (reservation.status == ReservationStatus.PENDING) return finish(reservation, ReservationStatus.RELEASED, "RELEASE_REQUESTED")
        if (reservation.status == ReservationStatus.RESERVED) {
            skus.lock(reservation.skuId) ?: error("예약 SKU가 없습니다")
            if (reservation.expiresAt <= clock.instant()) return releaseStock(reservation, ReservationStatus.EXPIRED, "TTL_EXPIRED")
            return releaseStock(reservation, ReservationStatus.RELEASED, "RELEASE_REQUESTED")
        }
        return response(reservation)
    }

    @PreAuthorize("hasAuthority('INVENTORY_SERVICE')")
    fun allocate(id: UUID, @Valid request: ReservationRequest): ReservationResponse {
        val reservation = locked(id)
        compare(reservation, request)
        if (reservation.status != ReservationStatus.RESERVED) return response(reservation)
        val sku = skus.lock(reservation.skuId) ?: error("예약 SKU가 없습니다")
        if (reservation.expiresAt <= clock.instant()) return releaseStock(reservation, ReservationStatus.EXPIRED, "TTL_EXPIRED")
        val warehouse = warehouses.findById(requireNotNull(reservation.warehouseId)).orElseThrow()
        val held = held(reservation)
        val today = LocalDate.now(clock)
        val eligible = sku.active && warehouse.active && warehouse.acceptsReservations && held.all { (line, balance) ->
            val lot = lots.findById(line.lotId).orElseThrow()
            lot.quality == LotQuality.GOOD && lot.expiresOn.isAfter(today) && balance.reserved >= line.quantity
        }
        if (!eligible) return releaseStock(reservation, ReservationStatus.INVALIDATED, "STOCK_INELIGIBLE")
        for ((line, balance) in held) {
            balance.reserved = Math.subtractExact(balance.reserved, line.quantity)
            balance.allocated = Math.addExact(balance.allocated, line.quantity)
            movement(reservation, balance, -line.quantity, line.quantity, "ALLOCATION")
        }
        return finish(reservation, ReservationStatus.ALLOCATED, null)
    }

    @PreAuthorize("hasAuthority('INVENTORY_SERVICE')")
    fun cancel(id: UUID, @Valid request: ReservationRequest): ReservationResponse {
        val reservation = initialize(id, request)
        if (reservation.status == ReservationStatus.ALLOCATED) {
            val outbound = outbounds.findById(id).orElse(null)
            if (outbound != null && outbound.status in setOf(OutboundStatus.PICKING, OutboundStatus.PACKED)) {
                if (!outbound.cancellationRequested) outbound.cancellationRequested = true
                return response(reservation)
            }
            if (outbound != null) outbound.status = OutboundStatus.CANCELED
            skus.lock(reservation.skuId) ?: error("예약 SKU가 없습니다")
            for ((line, balance) in held(reservation)) {
                check(balance.allocated >= line.quantity) { "할당 확보 기록과 잔량이 다릅니다" }
                balance.allocated = Math.subtractExact(balance.allocated, line.quantity)
                movement(reservation, balance, 0, -line.quantity, "ALLOCATION_CANCEL")
            }
            return finish(reservation, ReservationStatus.CANCELED, "CANCEL_REQUESTED")
        }
        if (reservation.status == ReservationStatus.RESERVED) {
            skus.lock(reservation.skuId) ?: error("예약 SKU가 없습니다")
            return releaseStock(reservation, ReservationStatus.CANCELED, "CANCEL_REQUESTED")
        }
        if (reservation.status == ReservationStatus.PENDING) return finish(reservation, ReservationStatus.CANCELED, "CANCEL_REQUESTED")
        return response(reservation)
    }

    // 스케줄러 전용 진입점이며 HTTP에 노출하지 않는다. 잠금 뒤 상태를 다시 확인한다.
    fun expire(id: UUID) {
        reservations.lock(id)?.let { current(it) }
    }

    private fun initialize(id: UUID, request: ReservationRequest): Reservation {
        val normalized = normalized(request)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        reservations.insertIfAbsent(id, normalized.orderAttemptId, normalized.orderItemId, normalized.skuId,
            normalized.quantity, normalized.expiresAt, mapper.writeValueAsString(normalized), now)
        return locked(id).also { compare(it, normalized) }
    }

    private fun normalized(request: ReservationRequest): ReservationRequest {
        require(request.quantity > 0 && request.expiresAt >= java.time.Instant.parse("1900-01-01T00:00:00Z") &&
            request.expiresAt < java.time.Instant.parse("9999-12-31T23:59:59Z")) { "예약 수량·시각이 잘못됐습니다" }
        return request.copy(expiresAt = request.expiresAt.truncatedTo(ChronoUnit.MICROS))
    }

    private fun compare(reservation: Reservation, request: ReservationRequest) {
        if (reservation.requestContent != mapper.writeValueAsString(normalized(request))) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "같은 예약 ID의 원본 내용이 다릅니다")
        }
    }

    private fun locked(id: UUID) = reservations.lock(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "예약 정보를 찾을 수 없습니다")

    private fun current(reservation: Reservation): ReservationResponse {
        if (reservation.status == ReservationStatus.RESERVED && reservation.expiresAt <= clock.instant()) {
            skus.lock(reservation.skuId) ?: error("예약 SKU가 없습니다")
            return releaseStock(reservation, ReservationStatus.EXPIRED, "TTL_EXPIRED")
        }
        return response(reservation)
    }

    private fun held(reservation: Reservation) = lines.findByReservationIdOrderByLotId(reservation.id).map { line ->
        line to (balances.findByWarehouseIdAndLotId(line.warehouseId, line.lotId) ?: error("예약 잔량이 없습니다"))
    }

    private fun releaseStock(reservation: Reservation, status: ReservationStatus, reason: String): ReservationResponse {
        val type = when (status) {
            ReservationStatus.EXPIRED -> "EXPIRATION"
            ReservationStatus.INVALIDATED -> "INVALIDATION"
            else -> "RELEASE"
        }
        for ((line, balance) in held(reservation)) {
            check(balance.reserved >= line.quantity) { "예약 확보 기록과 잔량이 다릅니다" }
            balance.reserved = Math.subtractExact(balance.reserved, line.quantity)
            movement(reservation, balance, -line.quantity, 0, type)
        }
        return finish(reservation, status, reason)
    }

    private fun movement(reservation: Reservation, balance: StockBalance, reserved: Long, allocated: Long, type: String) {
        ledger.save(StockLedger(documentType = type, documentId = reservation.id, skuId = reservation.skuId,
            warehouseId = balance.warehouseId, lotId = balance.lotId, onHandDelta = 0, blockedDelta = 0,
            reservedDelta = reserved, allocatedDelta = allocated, reason = "INVENTORY_$type", occurredAt = clock.instant()))
    }

    private fun finish(reservation: Reservation, status: ReservationStatus, reason: String?): ReservationResponse {
        reservation.status = status
        reservation.reason = reason
        reservation.updatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val result = response(reservation)
        outbox.record(listOf(OutboxEvent(aggregateType = "INVENTORY_RESERVATION", aggregateId = reservation.id,
            eventType = "INVENTORY_${status.name}", payload = mapper.writeValueAsString(mapOf("schemaVersion" to 1,
                "reservation" to result.reservation, "lines" to result.lines)))))
        return result
    }

    private fun response(reservation: Reservation): ReservationResponse {
        val outbound = outbounds.findById(reservation.id).orElse(null)
        val cancellation = when {
            reservation.status == ReservationStatus.CANCELED -> null
            reservation.status in setOf(ReservationStatus.SHIPPED, ReservationStatus.RETURNED) -> "REJECTED"
            outbound?.cancellationApproved == false -> "REJECTED"
            outbound?.cancellationRequested == true && outbound.cancellationApproved == null -> "PENDING"
            else -> null
        }
        return ReservationResponse(reservation, lines.findByReservationIdOrderByLotId(reservation.id), cancellation)
    }
}
