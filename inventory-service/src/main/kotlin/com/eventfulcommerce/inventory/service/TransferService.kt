package com.eventfulcommerce.inventory.service

import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Validated
@PreAuthorize("hasRole('ADMIN')")
@Transactional(rollbackFor = [Exception::class])
class TransferService(
    private val transfers: TransferRepository,
    private val lines: TransferLineRepository,
    private val receipts: TransferReceiptRepository,
    private val receiptLines: TransferReceiptLineRepository,
    private val warehouses: WarehouseRepository,
    private val skus: InventorySkuRepository,
    private val lots: InventoryLotRepository,
    private val balances: StockBalanceRepository,
    private val ledger: StockLedgerRepository,
    private val outbox: OutboxEventService,
    private val mapper: ObjectMapper,
    private val clock: Clock
) {
    fun create(id: UUID, @Valid request: TransferRequest): TransferResponse {
        require(request.sourceWarehouseId != request.destinationWarehouseId) { "출발·도착 창고는 달라야 합니다" }
        require(request.expectedArrivalAt >= Instant.parse("1900-01-01T00:00:00Z") &&
            request.expectedArrivalAt < Instant.parse("9999-12-31T23:59:59Z")) { "도착 예정 시각 범위가 잘못됐습니다" }
        val normalized = request.copy(expectedArrivalAt = request.expectedArrivalAt.truncatedTo(ChronoUnit.MICROS),
            lines = normalized(request.lines))
        val content = mapper.writeValueAsString(normalized)
        val actor = SecurityContextUtil.getCurrentUserId()
        val now = now()
        transfers.lock(id)?.let {
            conflict(it.requestContent == content, "같은 이동 ID의 원본 내용이 다릅니다")
            return response(it)
        }
        require(normalized.expectedArrivalAt > now) { "도착 예정 시각은 현재보다 늦어야 합니다" }
        for (warehouseId in listOf(normalized.sourceWarehouseId, normalized.destinationWarehouseId)) {
            val warehouse = warehouses.findById(warehouseId).orElseThrow { missing("창고") }
            conflict(warehouse.active, "비활성 창고에는 새 이동을 요청할 수 없습니다")
        }
        // 문서→정렬된 SKU→잔량 순서는 입고/고객 예약의 SKU 직렬화와 호환된다.
        val inserted = transfers.insertIfAbsent(id, normalized.sourceWarehouseId, normalized.destinationWarehouseId,
            normalized.expectedArrivalAt, actor, normalized.reason, content, now)
        val transfer = locked(id)
        conflict(transfer.requestContent == content, "같은 이동 ID의 원본 내용이 다릅니다")
        if (inserted == 0) return response(transfer)
        val selected = normalized.lines.associate { input -> input.lotId to
            lots.findById(input.lotId).orElseThrow { missing("로트") } }
        selected.values.map { it.skuId }.distinct().sorted().forEach { skus.lock(it) ?: throw missing("SKU") }
        for (input in normalized.lines) {
            val lot = selected.getValue(input.lotId)
            // shortcut: GOOD 로트만 이동한다. 검수/불량 이동이 필요하면 원 검수 창고 정책부터 확장한다.
            conflict(lot.quality == LotQuality.GOOD, "정상 GOOD 로트만 이동할 수 있습니다")
            val balance = balances.findByWarehouseIdAndLotId(transfer.sourceWarehouseId, lot.id)
                ?: throw missing("출발 창고 로트 잔량")
            conflict(input.quantity <= balance.onHand - balance.reserved - balance.allocated - balance.blocked - balance.transferHeld,
                "이동 가능한 미확보 수량이 부족합니다")
            balance.transferHeld = Math.addExact(balance.transferHeld, input.quantity)
            lines.save(TransferLine(transferId = id, sourceWarehouseId = transfer.sourceWarehouseId,
                skuId = lot.skuId, lotId = lot.id, quantity = input.quantity))
            movement(balance, id, "TRANSFER_HOLD", 0, input.quantity, actor, request.reason)
        }
        emit(transfer, "TRANSFER_HELD")
        return response(transfer)
    }

    fun dispatch(id: UUID, @Valid request: TransferActionRequest): TransferResponse {
        val transfer = locked(id)
        val content = mapper.writeValueAsString(request)
        if (transfer.dispatchContent != null) {
            conflict(transfer.dispatchContent == content, "출발 확정의 원본 내용이 다릅니다")
            return response(transfer)
        }
        conflict(transfer.status == TransferStatus.HELD, "확보 상태에서만 출발할 수 있습니다")
        val held = lockedLines(transfer)
        val actor = SecurityContextUtil.getCurrentUserId()
        for (line in held) {
            val balance = balances.findByWarehouseIdAndLotId(transfer.sourceWarehouseId, line.lotId)
                ?: error("이동 확보 잔량이 없습니다")
            check(balance.transferHeld >= line.quantity) { "이동 확보 기록과 잔량이 다릅니다" }
            balance.transferHeld = Math.subtractExact(balance.transferHeld, line.quantity)
            balance.onHand = Math.subtractExact(balance.onHand, line.quantity)
            movement(balance, id, "TRANSFER_DISPATCH", -line.quantity, -line.quantity, actor, request.reason)
        }
        transfer.status = TransferStatus.IN_TRANSIT
        transfer.dispatchContent = content
        transfer.dispatchedAt = now()
        transfer.dispatchedBy = actor
        emit(transfer, "TRANSFER_DISPATCHED")
        return response(transfer)
    }

    fun cancel(id: UUID, @Valid request: TransferActionRequest): TransferResponse {
        val transfer = locked(id)
        val content = mapper.writeValueAsString(request)
        if (transfer.cancellationContent != null) {
            conflict(transfer.cancellationContent == content, "이동 취소의 원본 내용이 다릅니다")
            return response(transfer)
        }
        conflict(transfer.status == TransferStatus.HELD, "출발 후에는 취소로 재고를 복원할 수 없습니다")
        val held = lockedLines(transfer)
        val actor = SecurityContextUtil.getCurrentUserId()
        for (line in held) {
            val balance = balances.findByWarehouseIdAndLotId(transfer.sourceWarehouseId, line.lotId)
                ?: error("이동 확보 잔량이 없습니다")
            check(balance.transferHeld >= line.quantity) { "이동 확보 기록과 잔량이 다릅니다" }
            balance.transferHeld = Math.subtractExact(balance.transferHeld, line.quantity)
            movement(balance, id, "TRANSFER_CANCEL", 0, -line.quantity, actor, request.reason)
        }
        transfer.status = TransferStatus.CANCELED
        transfer.cancellationContent = content
        transfer.canceledAt = now()
        transfer.canceledBy = actor
        emit(transfer, "TRANSFER_CANCELED")
        return response(transfer)
    }

    fun arrive(id: UUID, receiptId: UUID, @Valid request: TransferArrivalRequest): TransferReceiptResponse {
        val transfer = locked(id)
        val normalized = request.copy(lines = normalized(request.lines))
        val content = mapper.writeValueAsString(normalized)
        val existing = receipts.findById(receiptId).orElse(null)
        if (existing != null) {
            conflict(existing.transferId == id && existing.requestContent == content, "같은 도착 ID의 원본 내용이 다릅니다")
            return receiptResponse(existing)
        }
        conflict(transfer.status in setOf(TransferStatus.IN_TRANSIT, TransferStatus.PARTIALLY_RECEIVED), "출발한 미완료 이동만 도착할 수 있습니다")
        val actor = SecurityContextUtil.getCurrentUserId()
        val inserted = receipts.insertIfAbsent(receiptId, id, actor, request.reason, content, now())
        if (inserted == 0) {
            val receipt = receipts.findById(receiptId).orElseThrow()
            conflict(receipt.transferId == id && receipt.requestContent == content, "같은 도착 ID의 원본 내용이 다릅니다")
            return receiptResponse(receipt)
        }
        val held = lockedLines(transfer).associateBy { it.lotId }
        for (input in normalized.lines) {
            val line = held[input.lotId] ?: throw ResponseStatusException(HttpStatus.CONFLICT, "이동에 없는 로트입니다")
            conflict(input.quantity <= line.quantity - line.receivedQuantity, "미도착 수량을 초과할 수 없습니다")
            // 도착은 실물 기록이므로 기한/운영 상태와 무관하게 보유만 반영하고 판매 적격성을 유지한다.
            val balance = balances.findByWarehouseIdAndLotId(transfer.destinationWarehouseId, line.lotId)
                ?: balances.saveAndFlush(StockBalance(skuId = line.skuId, warehouseId = transfer.destinationWarehouseId, lotId = line.lotId))
            balance.onHand = Math.addExact(balance.onHand, input.quantity)
            line.receivedQuantity = Math.addExact(line.receivedQuantity, input.quantity)
            receiptLines.save(TransferReceiptLine(receiptId = receiptId, transferId = id, lotId = line.lotId, quantity = input.quantity))
            movement(balance, receiptId, "TRANSFER_ARRIVAL", input.quantity, 0, actor, request.reason)
        }
        transfer.status = if (held.values.all { it.receivedQuantity == it.quantity }) TransferStatus.RECEIVED else TransferStatus.PARTIALLY_RECEIVED
        emit(transfer, "TRANSFER_ARRIVED", receiptId)
        return receiptResponse(receipts.findById(receiptId).orElseThrow())
    }

    @Transactional(readOnly = true)
    fun list(warehouseId: UUID?, incompleteOnly: Boolean, overdueOnly: Boolean, page: Int, size: Int) =
        transfers.search(warehouseId, incompleteOnly, overdueOnly, now(), page(page, size))

    fun get(id: UUID) = response(locked(id))

    @Transactional(readOnly = true)
    fun receipts(id: UUID, page: Int, size: Int): org.springframework.data.domain.Page<TransferReceipt> {
        if (!transfers.existsById(id)) throw missing("이동")
        return receipts.findByTransferIdOrderByReceivedAtAscIdAsc(id, page(page, size))
    }

    @Transactional(readOnly = true)
    fun receipt(id: UUID, receiptId: UUID): TransferReceiptResponse {
        val receipt = receipts.findById(receiptId).orElseThrow { missing("도착") }
        if (receipt.transferId != id) throw missing("도착")
        return receiptResponse(receipt)
    }

    private fun locked(id: UUID) = transfers.lock(id) ?: throw missing("이동")
    private fun lockedLines(transfer: TransferOrder): List<TransferLine> {
        val held = lines.findByTransferIdOrderByLotId(transfer.id)
        held.map { it.skuId }.distinct().sorted().forEach { skus.lock(it) ?: error("이동 SKU가 없습니다") }
        return held
    }
    private fun normalized(lines: List<TransferQuantity>): List<TransferQuantity> {
        require(lines.size in 1..100 && lines.all { it.quantity > 0 }) { "이동 품목은 1~100줄, 수량은 양수여야 합니다" }
        require(lines.map { it.lotId }.distinct().size == lines.size) { "같은 로트는 한 줄로 입력해야 합니다" }
        return lines.sortedBy { it.lotId }
    }
    private fun response(transfer: TransferOrder): TransferResponse {
        val held = lines.findByTransferIdOrderByLotId(transfer.id)
        val lotMap = lots.findAllById(held.map { it.lotId }).associateBy { it.id }
        return TransferResponse(transfer, held.map { line -> TransferLineResponse(line, lotMap.getValue(line.lotId),
            if (transfer.status == TransferStatus.HELD) line.quantity else 0,
            if (transfer.status in setOf(TransferStatus.IN_TRANSIT, TransferStatus.PARTIALLY_RECEIVED)) line.quantity - line.receivedQuantity else 0) })
    }
    private fun receiptResponse(receipt: TransferReceipt) = TransferReceiptResponse(receipt, receiptLines.findByReceiptIdOrderByLotId(receipt.id))
    private fun movement(balance: StockBalance, documentId: UUID, type: String, onHand: Long, held: Long, actor: UUID, reason: String) {
        ledger.save(StockLedger(documentType = type, documentId = documentId, skuId = balance.skuId,
            warehouseId = balance.warehouseId, lotId = balance.lotId, onHandDelta = onHand, blockedDelta = 0,
            transferHeldDelta = held, actorId = actor, reason = reason, occurredAt = now()))
    }
    private fun emit(transfer: TransferOrder, type: String, receiptId: UUID? = null) {
        outbox.record(listOf(OutboxEvent(aggregateType = "INVENTORY_TRANSFER", aggregateId = transfer.id, eventType = type,
            payload = mapper.writeValueAsString(mapOf("schemaVersion" to 1, "transfer" to response(transfer), "receiptId" to receiptId)))))
    }
    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)
    private fun page(page: Int, size: Int): PageRequest {
        require(page >= 0 && size in 1..200) { "page는 0 이상, size는 1~200이어야 합니다" }
        return PageRequest.of(page, size)
    }
    private fun missing(target: String) = ResponseStatusException(HttpStatus.NOT_FOUND, "$target 정보를 찾을 수 없습니다")
    private fun conflict(valid: Boolean, message: String) { if (!valid) throw ResponseStatusException(HttpStatus.CONFLICT, message) }
}
