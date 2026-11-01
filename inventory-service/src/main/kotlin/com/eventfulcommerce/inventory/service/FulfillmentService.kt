package com.eventfulcommerce.inventory.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.inventory.domain.*
import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.repository.*
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Service
@Validated
@Transactional(rollbackFor = [Exception::class])
class FulfillmentService(
    private val reservations: ReservationRepository,
    private val lines: ReservationLineRepository,
    private val outbounds: OutboundRepository,
    private val returns: ReturnReceiptRepository,
    private val skus: InventorySkuRepository,
    private val warehouses: WarehouseRepository,
    private val lots: InventoryLotRepository,
    private val balances: StockBalanceRepository,
    private val ledger: StockLedgerRepository,
    private val outbox: OutboxEventService,
    private val mapper: ObjectMapper,
    private val clock: Clock
) {
    // Kafka 진입점. 예약 잠금은 취소가 출고 지시 생성보다 먼저 와도 종결 결과를 보존한다.
    fun create(payload: DirectFulfillmentPayload) {
        val reservation = lock(payload.reservationId)
        check(reservation.orderAttemptId == payload.orderId && reservation.orderItemId == payload.orderItemId &&
            reservation.skuId == payload.skuId && reservation.warehouseId == payload.warehouseId && reservation.quantity == payload.quantity)
        val content = mapper.writeValueAsString(payload)
        outbounds.findById(reservation.id).orElse(null)?.let { check(it.requestContent == content); return }
        if (reservation.status == ReservationStatus.CANCELED) return
        check(reservation.status == ReservationStatus.ALLOCATED)
        outbounds.saveAndFlush(OutboundOrder(reservation.id, payload.orderId, payload.sellerOrderId, payload.orderItemId,
            payload.warehouseId, content, OutboundStatus.READY, createdAt = clock.instant()))
        event(reservation, "OUTBOUND_READY", payload)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    fun list(orderId: UUID, page: Int, size: Int): org.springframework.data.domain.Page<OutboundOrder> {
        require(page >= 0 && size in 1..100)
        return outbounds.findByOrderIdOrderById(orderId, PageRequest.of(page, size))
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    fun get(id: UUID) = OutboundResponse(outbound(id), lines.findByReservationIdOrderByLotId(id))

    @PreAuthorize("hasRole('ADMIN')")
    fun act(id: UUID, action: String, @Valid request: OutboundActionRequest): OutboundResponse {
        val reservation = lock(id)
        val outbound = outbound(id)
        val normalized = request.copy(lines = request.lines.sortedBy { it.lotId })
        val content = mapper.writeValueAsString(normalized)
        val previous = when (action) { "pick" -> outbound.pickContent; "pack" -> outbound.packContent; "ship" -> outbound.shipContent; else -> error("알 수 없는 창고 작업") }
        if (previous != null) { conflict(previous == content, "같은 창고 작업의 내용이 다릅니다"); return get(id) }
        conflict(reservation.status == ReservationStatus.ALLOCATED &&
            !(outbound.cancellationRequested && outbound.cancellationApproved != false), "취소 중이거나 종결된 출고입니다")
        val expected = when (action) { "pick" -> OutboundStatus.READY; "pack" -> OutboundStatus.PICKING; else -> OutboundStatus.PACKED }
        conflict(outbound.status == expected, "창고 작업 순서가 잘못됐습니다")
        val held = held(reservation)
        conflict(normalized.lines == held.map { LotQuantity(it.first.lotId, it.first.quantity) }, "확보한 로트·전량을 확인해야 합니다")
        conflict(eligible(reservation, held) && held.all { (line, balance) -> balance.allocated >= line.quantity },
            "출고할 수 없는 재고입니다. 출고를 중단하고 창고 취소로 처리하세요")
        when (action) {
            "pick" -> { outbound.status = OutboundStatus.PICKING; outbound.pickContent = content }
            "pack" -> { outbound.status = OutboundStatus.PACKED; outbound.packContent = content }
            "ship" -> {
                held.forEach { (line, balance) ->
                    balance.onHand = Math.subtractExact(balance.onHand, line.quantity)
                    balance.allocated = Math.subtractExact(balance.allocated, line.quantity)
                    movement(reservation, balance, -line.quantity, -line.quantity, 0, "OUTBOUND", request.reason)
                }
                outbound.status = OutboundStatus.SHIPPED
                outbound.shipContent = content
                outbound.shippedAt = clock.instant()
                reservation.status = ReservationStatus.SHIPPED
            }
        }
        reservation.updatedAt = clock.instant()
        event(reservation, "OUTBOUND_${outbound.status.name}", payload(outbound))
        return get(id)
    }

    @PreAuthorize("hasRole('ADMIN')")
    fun decide(id: UUID, @Valid request: CancellationDecisionRequest): OutboundResponse {
        val reservation = lock(id)
        val outbound = outbound(id)
        val content = mapper.writeValueAsString(request)
        if (outbound.decisionContent != null) {
            conflict(outbound.decisionContent == content, "확정된 창고 취소 결정은 바꿀 수 없습니다")
            return get(id)
        }
        conflict(reservation.status == ReservationStatus.ALLOCATED && outbound.cancellationRequested &&
            outbound.status in setOf(OutboundStatus.PICKING, OutboundStatus.PACKED), "창고 확인 대기 상태가 아닙니다")
        outbound.cancellationApproved = request.approved
        outbound.decisionContent = content
        if (request.approved) cancelAllocation(reservation, outbound, held(reservation), request.reason)
        reservation.updatedAt = clock.instant()
        event(reservation, "OUTBOUND_CANCELLATION_DECIDED", payload(outbound))
        return get(id)
    }

    @PreAuthorize("hasRole('ADMIN')")
    fun cancelIneligible(id: UUID, @Valid request: OutboundActionRequest): OutboundResponse {
        val reservation = lock(id)
        val outbound = outbound(id)
        val normalized = request.copy(lines = request.lines.sortedBy { it.lotId })
        val content = mapper.writeValueAsString(normalized)
        outbound.ineligibilityCancellationContent?.let {
            conflict(it == content, "확정된 부적격 재고 회수의 내용이 다릅니다")
            return get(id)
        }
        conflict(reservation.status == ReservationStatus.ALLOCATED && outbound.cancellationApproved == false &&
            outbound.status in setOf(OutboundStatus.PICKING, OutboundStatus.PACKED), "거절 후 출고 전인 품목만 회수할 수 있습니다")
        val held = held(reservation)
        conflict(normalized.lines == held.map { LotQuantity(it.first.lotId, it.first.quantity) }, "확보한 로트·전량을 확인해야 합니다")
        conflict(!eligible(reservation, held), "출고 부적격 재고만 별도 회수할 수 있습니다")
        cancelAllocation(reservation, outbound, held, request.reason)
        outbound.ineligibilityCancellationContent = content
        reservation.updatedAt = clock.instant()
        event(reservation, "OUTBOUND_INELIGIBILITY_CANCELED", payload(outbound))
        return get(id)
    }

    @PreAuthorize("hasRole('ADMIN')")
    fun requestReturn(id: UUID, @Valid request: ReturnRequest): ReturnReceipt {
        val reservation = lock(request.outboundId)
        val outbound = outbound(request.outboundId)
        val existing = returns.findById(id).orElse(null)
        if (existing != null) {
            conflict(existing.outboundId == request.outboundId && existing.reason == request.reason, "같은 반품 ID의 내용이 다릅니다")
            return existing
        }
        conflict(reservation.status == ReservationStatus.SHIPPED && outbound.status == OutboundStatus.SHIPPED, "실제 출고한 품목만 반품할 수 있습니다")
        conflict(returns.findByOutboundId(outbound.id) == null, "이미 반품 접수한 출고입니다")
        returns.insertIfAbsent(id, outbound.id, request.reason, SecurityContextUtil.getCurrentUserId(), clock.instant())
        val receipt = returns.findById(id).orElseThrow { ResponseStatusException(HttpStatus.CONFLICT, "이미 반품 접수한 출고입니다") }
        conflict(receipt.outboundId == outbound.id && receipt.reason == request.reason, "같은 반품 ID의 내용이 다릅니다")
        return receipt
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    fun getReturn(id: UUID): ReturnReceipt = returns.findById(id).orElseThrow { missing() }

    @PreAuthorize("hasRole('ADMIN')")
    fun receiveReturn(id: UUID): ReturnReceipt {
        lock(returns.outboundId(id) ?: throw missing())
        val receipt = returns.findById(id).orElseThrow()
        if (receipt.status == ReturnStatus.REQUESTED) { receipt.status = ReturnStatus.RECEIVED; receipt.receivedAt = clock.instant() }
        return receipt
    }

    @PreAuthorize("hasRole('ADMIN')")
    fun inspectReturn(id: UUID, @Valid request: ReturnInspectionRequest): ReturnReceipt {
        val reservation = lock(returns.outboundId(id) ?: throw missing())
        val receipt = returns.findById(id).orElseThrow()
        val normalized = request.copy(lines = request.lines.sortedBy { it.lotId })
        val content = mapper.writeValueAsString(normalized)
        if (receipt.inspectionContent != null) {
            conflict(receipt.inspectionContent == content, "같은 반품 검수의 내용이 다릅니다")
            return receipt
        }
        conflict(receipt.status == ReturnStatus.RECEIVED && reservation.status == ReservationStatus.SHIPPED, "수령한 반품만 검수할 수 있습니다")
        val held = held(reservation)
        conflict(normalized.lines.map { LotQuantity(it.lotId, Math.addExact(it.good, it.damaged)) } ==
            held.map { LotQuantity(it.first.lotId, it.first.quantity) }, "원 출고 로트별 전량 검수가 필요합니다")
        for ((line, sourceBalance) in held) {
            val result = normalized.lines.single { it.lotId == line.lotId }
            val source = lots.findById(line.lotId).orElseThrow()
            for ((quality, quantity) in listOf(LotQuality.GOOD to result.good, LotQuality.DAMAGED to result.damaged)) {
                if (quantity == 0L) continue
                val lot = if (quality == LotQuality.GOOD) source else lots.findBySkuIdAndSupplierIdAndCodeAndQuality(source.skuId, source.supplierId, source.code, quality)
                    ?: lots.saveAndFlush(InventoryLot(skuId = source.skuId, supplierId = source.supplierId, code = source.code, expiresOn = source.expiresOn, quality = quality))
                conflict(lot.expiresOn == source.expiresOn, "같은 반품 로트의 사용기한이 다릅니다")
                val balance = if (quality == LotQuality.GOOD) sourceBalance else balances.findByWarehouseIdAndLotId(line.warehouseId, lot.id)
                    ?: balances.saveAndFlush(StockBalance(skuId = source.skuId, warehouseId = line.warehouseId, lotId = lot.id))
                balance.onHand = Math.addExact(balance.onHand, quantity)
                val blocked = if (quality == LotQuality.GOOD) 0L else quantity
                balance.blocked = Math.addExact(balance.blocked, blocked)
                movement(reservation, balance, quantity, 0, blocked, "RETURN", request.reason, id)
            }
        }
        receipt.status = ReturnStatus.INSPECTED
        receipt.inspectionContent = content
        receipt.inspectedAt = clock.instant()
        reservation.status = ReservationStatus.RETURNED
        reservation.updatedAt = clock.instant()
        event(reservation, "DIRECT_RETURN_INSPECTED", payload(outbound(reservation.id)))
        return receipt
    }

    fun delivered(payload: DirectFulfillmentPayload) {
        lock(payload.reservationId)
        val outbound = outbound(payload.reservationId)
        check(outbound.requestContent == mapper.writeValueAsString(payload) && outbound.status == OutboundStatus.SHIPPED)
        if (outbound.deliveredAt == null) outbound.deliveredAt = clock.instant()
    }

    private fun lock(id: UUID) = reservations.lock(id) ?: throw missing()
    private fun outbound(id: UUID) = outbounds.findById(id).orElseThrow { missing() }
    private fun held(r: Reservation): List<Pair<ReservationLine, StockBalance>> {
        skus.lock(r.skuId) ?: error("할당 SKU가 없습니다")
        // 요청 정규화와 동일한 UUID 순서로 비교한다. PostgreSQL UUID 정렬은 Java와 다르다.
        return lines.findByReservationIdOrderByLotId(r.id).sortedBy { it.lotId }.map { it to (balances.findByWarehouseIdAndLotId(it.warehouseId, it.lotId) ?: error("할당 잔량이 없습니다")) }
    }
    private fun eligible(r: Reservation, held: List<Pair<ReservationLine, StockBalance>>): Boolean {
        val sku = skus.findById(r.skuId).orElseThrow()
        val warehouse = warehouses.findById(requireNotNull(r.warehouseId)).orElseThrow()
        return sku.active && warehouse.active && warehouse.acceptsReservations && held.all { (line, _) ->
            val lot = lots.findById(line.lotId).orElseThrow()
            lot.quality == LotQuality.GOOD && lot.expiresOn.isAfter(LocalDate.now(clock))
        }
    }
    private fun cancelAllocation(r: Reservation, outbound: OutboundOrder, held: List<Pair<ReservationLine, StockBalance>>, reason: String) {
        held.forEach { (line, balance) ->
            check(balance.allocated >= line.quantity)
            balance.allocated = Math.subtractExact(balance.allocated, line.quantity)
            movement(r, balance, 0, -line.quantity, 0, "ALLOCATION_CANCEL", reason)
        }
        r.status = ReservationStatus.CANCELED
        outbound.status = OutboundStatus.CANCELED
    }
    private fun payload(outbound: OutboundOrder) = mapper.readValue(outbound.requestContent, DirectFulfillmentPayload::class.java)
    private fun movement(r: Reservation, b: StockBalance, onHand: Long, allocated: Long, blocked: Long, type: String, reason: String, documentId: UUID = r.id) {
        ledger.save(StockLedger(documentType = type, documentId = documentId, skuId = r.skuId, warehouseId = b.warehouseId,
            lotId = b.lotId, onHandDelta = onHand, allocatedDelta = allocated, blockedDelta = blocked,
            actorId = SecurityContextUtil.getCurrentUserId(), reason = reason, occurredAt = clock.instant()))
    }
    private fun event(r: Reservation, type: String, payload: DirectFulfillmentPayload) {
        outbox.record(listOf(OutboxEvent(aggregateType = "DIRECT_OUTBOUND", aggregateId = r.orderAttemptId,
            eventType = type, payload = mapper.writeValueAsString(payload))))
    }
    private fun conflict(condition: Boolean, message: String) { if (!condition) throw ResponseStatusException(HttpStatus.CONFLICT, message) }
    private fun missing() = ResponseStatusException(HttpStatus.NOT_FOUND, "출고·반품 정보를 찾을 수 없습니다")
}
