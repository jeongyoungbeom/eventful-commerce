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
import java.time.LocalDate
import java.util.UUID

@Service
@Validated
@PreAuthorize("hasRole('ADMIN')")
@Transactional(rollbackFor = [Exception::class])
class InventoryService(
    private val warehouses: WarehouseRepository,
    private val suppliers: SupplierRepository,
    private val skus: InventorySkuRepository,
    private val orders: PurchaseOrderRepository,
    private val purchaseLines: PurchaseLineRepository,
    private val lots: InventoryLotRepository,
    private val balances: StockBalanceRepository,
    private val receipts: InboundReceiptRepository,
    private val receiptLines: ReceiptLineRepository,
    private val ledger: StockLedgerRepository,
    private val inspections: InspectionRepository,
    private val outbox: OutboxEventService,
    private val mapper: ObjectMapper,
    private val clock: Clock
) {
    fun createWarehouse(@Valid request: WarehouseRequest): Warehouse = warehouses.save(Warehouse(
        code = request.code, name = request.name, address = request.address, active = request.active,
        acceptsReservations = request.acceptsReservations, priority = request.priority))

    fun createSupplier(@Valid request: SupplierRequest): Supplier = suppliers.save(Supplier(code = request.code, name = request.name))

    @Transactional(readOnly = true) fun warehouses(page: Int, size: Int) = warehouses.findAll(page(page, size))
    @Transactional(readOnly = true) fun suppliers(page: Int, size: Int) = suppliers.findAll(page(page, size))
    @Transactional(readOnly = true) fun skus(page: Int, size: Int) = skus.findAll(page(page, size))

    fun createPurchaseOrder(id: UUID, @Valid request: PurchaseOrderRequest): PurchaseOrderResponse {
        require(request.lines.map { it.skuId }.distinct().size == request.lines.size) { "발주 SKU는 중복할 수 없습니다" }
        request.lines.fold(0L) { sum, line -> Math.addExact(sum, Math.multiplyExact(line.quantity, line.unitCost)) }
        val normalized = request.copy(lines = request.lines.sortedBy { it.skuId })
        val content = mapper.writeValueAsString(normalized)
        val existing = orders.findById(id).orElse(null)
        if (existing != null) {
            conflictUnless(existing.requestContent == content, "같은 발주 ID의 내용이 다릅니다")
            return purchaseOrder(id)
        }
        notFound(suppliers.existsById(request.supplierId), "공급사")
        val warehouse = warehouses.findById(request.warehouseId).orElseThrow { missing("창고") }
        conflictUnless(warehouse.active, "비활성 창고에는 발주할 수 없습니다")
        normalized.lines.forEach { line ->
            val sku = skus.findById(line.skuId).orElseThrow { missing("직매입 SKU") }
            conflictUnless(sku.active, "비활성 SKU에는 발주할 수 없습니다")
        }
        val inserted = orders.insertIfAbsent(id, request.supplierId, request.warehouseId,
            SecurityContextUtil.getCurrentUserId(), content, clock.instant())
        if (inserted == 1) {
            purchaseLines.saveAll(normalized.lines.map { PurchaseLine(purchaseOrderId = id,
                skuId = it.skuId, orderedQuantity = it.quantity, unitCost = it.unitCost) })
        } else {
            conflictUnless(orders.findById(id).orElseThrow().requestContent == content, "같은 발주 ID의 내용이 다릅니다")
        }
        return purchaseOrder(id)
    }

    @Transactional(readOnly = true)
    fun purchaseOrder(id: UUID): PurchaseOrderResponse {
        val order = orders.findById(id).orElseThrow { missing("발주") }
        val lines = purchaseLines.findByPurchaseOrderIdOrderBySkuId(id)
        val status = when {
            lines.all { it.receivedQuantity == it.orderedQuantity } -> "RECEIVED"
            lines.any { it.receivedQuantity > 0 } -> "PARTIALLY_RECEIVED"
            else -> "OPEN"
        }
        return PurchaseOrderResponse(order, lines, status)
    }

    fun receive(id: UUID, @Valid request: InboundRequest): ReceiptResponse {
        require(request.lines.all { it.expiresOn.year in 1900..9999 }) { "사용기한 범위는 1900~9999년입니다" }
        require(request.lines.map { Triple(it.purchaseLineId, it.lotCode, it.quality) }.distinct().size == request.lines.size) {
            "같은 발주 품목·로트·품질은 한 줄로 입력해야 합니다"
        }
        val normalized = request.copy(lines = request.lines.sortedWith(compareBy(
            { it.purchaseLineId }, { it.lotCode }, { it.quality.name })))
        val content = mapper.writeValueAsString(normalized)
        val actor = SecurityContextUtil.getCurrentUserId()
        val now = clock.instant()
        val existing = receipts.findById(id).orElse(null)
        if (existing != null) {
            conflictUnless(existing.requestContent == content, "같은 입고 ID의 내용이 다릅니다")
            return receipt(id)
        }
        // 자식 문서의 FK 잠금을 얻기 전에 발주를 잠가 동시 입고의 잠금 승격 교착을 피한다.
        val order = orders.lock(request.purchaseOrderId) ?: throw missing("발주")
        val inserted = receipts.insertIfAbsent(id, request.purchaseOrderId, actor, request.reason, content, now)
        if (inserted == 0) {
            conflictUnless(receipts.findById(id).orElseThrow().requestContent == content, "같은 입고 ID의 내용이 다릅니다")
            return receipt(id)
        }
        val warehouse = warehouses.findById(order.warehouseId).orElseThrow { missing("창고") }
        conflictUnless(warehouse.active, "비활성 창고에는 입고할 수 없습니다")
        val lines = purchaseLines.findByPurchaseOrderIdOrderBySkuId(order.id).associateBy { it.id }
        require(normalized.lines.all { it.purchaseLineId in lines }) { "다른 발주의 품목입니다" }
        // 서로 다른 발주도 같은 SKU 순서로 잠가 로트/잔량의 부재 경합을 보호한다.
        normalized.lines.map { lines.getValue(it.purchaseLineId).skuId }.distinct().sorted().forEach {
            val sku = skus.lock(it) ?: throw missing("직매입 SKU")
            conflictUnless(sku.active, "비활성 SKU에는 입고할 수 없습니다")
        }
        var amount = 0L
        val savedLines = normalized.lines.map { input ->
            val line = lines.getValue(input.purchaseLineId)
            conflictUnless(input.quantity <= line.orderedQuantity - line.receivedQuantity, "초과 입고는 허용하지 않습니다")
            line.receivedQuantity = Math.addExact(line.receivedQuantity, input.quantity)
            val lot = lots.findBySkuIdAndSupplierIdAndCodeAndQuality(line.skuId, order.supplierId, input.lotCode, input.quality)
                ?: lots.saveAndFlush(InventoryLot(skuId = line.skuId, supplierId = order.supplierId,
                    code = input.lotCode, expiresOn = input.expiresOn, quality = input.quality))
            conflictUnless(lot.expiresOn == input.expiresOn, "기존 로트의 사용기한은 변경할 수 없습니다")
            val balance = balances.findByWarehouseIdAndLotId(order.warehouseId, lot.id)
                ?: balances.save(StockBalance(skuId = line.skuId, warehouseId = order.warehouseId, lotId = lot.id))
            val blocked = if (input.quality == LotQuality.GOOD) 0L else input.quantity
            balance.onHand = Math.addExact(balance.onHand, input.quantity)
            balance.blocked = Math.addExact(balance.blocked, blocked)
            val cost = Math.multiplyExact(input.quantity, line.unitCost)
            amount = Math.addExact(amount, cost)
            val received = receiptLines.save(ReceiptLine(receiptId = id, purchaseLineId = line.id,
                lotId = lot.id, quantity = input.quantity, unitCost = line.unitCost, purchaseAmount = cost))
            ledger.save(StockLedger(receiptId = id, receiptLineId = received.id, skuId = line.skuId,
                warehouseId = order.warehouseId, lotId = lot.id, onHandDelta = input.quantity,
                blockedDelta = blocked, actorId = actor, reason = request.reason, occurredAt = now))
            ReceiptLineResponse(received, lot)
        }
        outbox.record(listOf(OutboxEvent(aggregateType = "INVENTORY_RECEIPT", aggregateId = id,
            eventType = "INVENTORY_RECEIVED", payload = mapper.writeValueAsString(mapOf(
                "schemaVersion" to 1, "receiptId" to id, "purchaseOrderId" to order.id,
                "supplierId" to order.supplierId, "warehouseId" to order.warehouseId,
                "actorId" to actor, "purchaseAmount" to amount, "lines" to savedLines)))))
        return receipt(id)
    }

    fun inspect(id: UUID, @Valid request: InspectionRequest): InspectionDecision {
        val content = mapper.writeValueAsString(request)
        val existing = inspections.findById(id).orElse(null)
        if (existing != null) {
            conflictUnless(existing.requestContent == content, "같은 검수 ID의 내용이 다릅니다")
            return existing
        }
        val line = receiptLines.findById(request.receiptLineId).orElseThrow { missing("입고 품목") }
        val receipt = receipts.findById(line.receiptId).orElseThrow { missing("입고") }
        val order = orders.lock(receipt.purchaseOrderId) ?: throw missing("발주")
        val sourceLot = lots.findById(line.lotId).orElseThrow()
        require(sourceLot.quality == LotQuality.INSPECTION) { "검수 중 입고 품목만 확정할 수 있습니다" }
        require(Math.addExact(request.goodQuantity, request.damagedQuantity) == line.quantity) { "검수 결과 합계가 원 입고 수량과 달라집니다" }
        skus.lock(sourceLot.skuId) ?: throw missing("직매입 SKU")
        val actor = SecurityContextUtil.getCurrentUserId()
        val now = clock.instant()
        val inserted = inspections.insertIfAbsent(id, line.id, request.goodQuantity, request.damagedQuantity,
            actor, request.reason, content, now)
        if (inserted == 0) {
            val decided = inspections.findById(id).orElseThrow { ResponseStatusException(HttpStatus.CONFLICT, "이미 검수한 입고 품목입니다") }
            conflictUnless(decided.requestContent == content, "같은 검수 ID의 내용이 다릅니다")
            return decided
        }
        val source = balances.findByWarehouseIdAndLotId(order.warehouseId, sourceLot.id) ?: throw missing("검수 재고")
        conflictUnless(source.onHand >= line.quantity && source.blocked >= line.quantity, "검수 중 잔량이 부족합니다")
        source.onHand = Math.subtractExact(source.onHand, line.quantity)
        source.blocked = Math.subtractExact(source.blocked, line.quantity)
        ledger.save(StockLedger(receiptId = receipt.id, receiptLineId = line.id, documentType = "INSPECTION", documentId = id,
            skuId = sourceLot.skuId, warehouseId = order.warehouseId, lotId = sourceLot.id,
            onHandDelta = -line.quantity, blockedDelta = -line.quantity, actorId = actor, reason = request.reason, occurredAt = now))
        for ((quality, quantity) in listOf(LotQuality.GOOD to request.goodQuantity, LotQuality.DAMAGED to request.damagedQuantity)) {
            if (quantity == 0L) continue
            val lot = lots.findBySkuIdAndSupplierIdAndCodeAndQuality(sourceLot.skuId, sourceLot.supplierId, sourceLot.code, quality)
                ?: lots.saveAndFlush(InventoryLot(skuId = sourceLot.skuId, supplierId = sourceLot.supplierId,
                    code = sourceLot.code, expiresOn = sourceLot.expiresOn, quality = quality))
            conflictUnless(lot.expiresOn == sourceLot.expiresOn, "같은 로트의 사용기한이 다릅니다")
            val balance = balances.findByWarehouseIdAndLotId(order.warehouseId, lot.id)
                ?: balances.save(StockBalance(skuId = sourceLot.skuId, warehouseId = order.warehouseId, lotId = lot.id))
            val blocked = if (quality == LotQuality.DAMAGED) quantity else 0L
            balance.onHand = Math.addExact(balance.onHand, quantity)
            balance.blocked = Math.addExact(balance.blocked, blocked)
            ledger.save(StockLedger(receiptId = receipt.id, receiptLineId = line.id, documentType = "INSPECTION", documentId = id,
                skuId = sourceLot.skuId, warehouseId = order.warehouseId, lotId = lot.id,
                onHandDelta = quantity, blockedDelta = blocked, actorId = actor, reason = request.reason, occurredAt = now))
        }
        val result = inspections.findById(id).orElseThrow()
        outbox.record(listOf(OutboxEvent(aggregateType = "INVENTORY_INSPECTION", aggregateId = id,
            eventType = "INVENTORY_INSPECTED", payload = mapper.writeValueAsString(mapOf("schemaVersion" to 1, "inspection" to result)))))
        return result
    }

    @Transactional(readOnly = true)
    fun inspection(id: UUID) = inspections.findById(id).orElseThrow { missing("검수") }

    @Transactional(readOnly = true)
    fun receipt(id: UUID): ReceiptResponse {
        val receipt = receipts.findById(id).orElseThrow { missing("입고") }
        val lines = receiptLines.findByReceiptIdOrderById(id).map { ReceiptLineResponse(it, lots.findById(it.lotId).orElseThrow()) }
        return ReceiptResponse(receipt, lines, lines.fold(0L) { sum, it -> Math.addExact(sum, it.line.purchaseAmount) })
    }

    @Transactional(readOnly = true)
    fun stock(skuId: UUID, warehouseId: UUID?, page: Int, size: Int): org.springframework.data.domain.Page<StockResponse> {
        val result = balances.stock(skuId, warehouseId, page(page, size))
        val lotMap = lots.findAllById(result.content.map { it.lotId }).associateBy { it.id }
        val warehouseMap = warehouses.findAllById(result.content.map { it.warehouseId }.distinct()).associateBy { it.id }
        val activeSku = skus.findById(skuId).orElse(null)?.active == true
        val today = LocalDate.now(clock)
        return result.map {
            val lot = lotMap.getValue(it.lotId)
            val warehouse = warehouseMap.getValue(it.warehouseId)
            val eligible = lot.quality == LotQuality.GOOD && lot.expiresOn.isAfter(today) &&
                warehouse.active && warehouse.acceptsReservations && activeSku
            StockResponse(it, lot, if (eligible) it.onHand - it.reserved - it.allocated - it.blocked - it.transferHeld else 0L)
        }
    }

    @Transactional(readOnly = true)
    fun stockSummary(skuId: UUID, warehouseId: UUID?) = balances.summary(skuId, warehouseId, LocalDate.now(clock)).map {
        StockSummary(skuId, it.warehouseId, it.onHand.longValueExact(), it.reserved.longValueExact(),
            it.allocated.longValueExact(), it.blocked.longValueExact(), it.available.longValueExact(), it.transferHeld.longValueExact())
    }

    @Transactional(readOnly = true)
    fun ledger(skuId: UUID, page: Int, size: Int) = ledger.findBySkuIdOrderByOccurredAtAscIdAsc(skuId, page(page, size))

    private fun page(page: Int, size: Int): PageRequest {
        require(page >= 0 && size in 1..200) { "page는 0 이상, size는 1~200이어야 합니다" }
        return PageRequest.of(page, size)
    }
    private fun missing(target: String) = ResponseStatusException(HttpStatus.NOT_FOUND, "$target 정보를 찾을 수 없습니다")
    private fun notFound(found: Boolean, target: String) { if (!found) throw missing(target) }
    private fun conflictUnless(valid: Boolean, message: String) { if (!valid) throw ResponseStatusException(HttpStatus.CONFLICT, message) }
}
