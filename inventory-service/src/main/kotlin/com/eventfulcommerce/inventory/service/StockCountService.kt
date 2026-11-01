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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Validated
@PreAuthorize("hasRole('ADMIN')")
@Transactional(rollbackFor = [Exception::class])
class StockCountService(
    private val counts: StockCountRepository,
    private val reports: ReconciliationRepository,
    private val skus: InventorySkuRepository,
    private val lots: InventoryLotRepository,
    private val balances: StockBalanceRepository,
    private val ledger: StockLedgerRepository,
    private val outbox: OutboxEventService,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val clock: Clock
) {
    fun start(id: UUID, @Valid request: StockCountRequest): StockCount {
        val content = mapper.writeValueAsString(request)
        counts.lock(id)?.let {
            conflict(it.requestContent == content, "같은 실사 ID의 기준 요청이 다릅니다")
            return it
        }
        val lot = lots.findById(request.lotId).orElseThrow { missing("로트") }
        skus.lock(lot.skuId) ?: throw missing("SKU")
        val balance = balances.findByWarehouseIdAndLotId(request.warehouseId, lot.id) ?: throw missing("창고 로트 재고")
        conflict(lot.quality != LotQuality.INSPECTION, "검수 중 로트는 검수 확정 후 실사하세요")
        uncommitted(balance)
        conflict(balance.blocked == if (lot.quality == LotQuality.DAMAGED) balance.onHand else 0L,
            "품질별 판매불가 수량을 먼저 조사하세요")
        request.reconciliationId?.let { reportId ->
            val report = reports.findById(reportId).orElseThrow { missing("대사 보고서") }
            conflict(report.skuId == lot.skuId && mapper.readTree(report.resultContent).any {
                it["warehouseId"].asText() == request.warehouseId.toString() && it["lotId"].asText() == lot.id.toString()
            }, "대사 보고서에 없는 재고입니다")
        }
        counts.insertIfAbsent(id, lot.skuId, request.warehouseId, lot.id, balance.onHand, balance.blocked,
            balance.version, now(), actor(), request.reason, request.reconciliationId, content)
        val count = locked(id)
        conflict(count.requestContent == content, "같은 실사 ID의 기준 요청이 다릅니다")
        return count
    }

    fun measure(id: UUID, @Valid request: StockCountMeasurement): StockCount {
        val count = locked(id)
        val content = mapper.writeValueAsString(request)
        count.measurementContent?.let {
            conflict(it == content, "이미 기록한 측정 결과가 다릅니다. 취소 후 다시 실사하세요")
            return count
        }
        conflict(count.status == StockCountStatus.OPEN, "진행 중 실사만 측정할 수 있습니다")
        count.countedQuantity = request.countedQuantity
        count.measuredAt = now()
        count.measuredBy = actor()
        count.measurementReason = request.reason
        count.measurementContent = content
        count.status = StockCountStatus.MEASURED
        return count
    }

    fun approve(id: UUID, @Valid request: StockCountAction): StockCount {
        val count = locked(id)
        val content = mapper.writeValueAsString(request)
        count.approvalContent?.let {
            conflict(it == content, "이미 승인한 조정의 사유가 다릅니다")
            return count
        }
        conflict(count.status == StockCountStatus.MEASURED, "측정이 끝난 실사만 승인할 수 있습니다")
        skus.lock(count.skuId) ?: throw missing("SKU")
        val balance = balances.findByWarehouseIdAndLotId(count.warehouseId, count.lotId) ?: throw missing("재고")
        uncommitted(balance)
        conflict(balance.version == count.baseVersion && balance.onHand == count.baseOnHand && balance.blocked == count.baseBlocked,
            "실사 기준 이후 재고가 변했습니다. 취소 후 새 실사를 시작하세요")
        val quantity = requireNotNull(count.countedQuantity)
        val lot = lots.findById(count.lotId).orElseThrow { missing("로트") }
        val delta = Math.subtractExact(quantity, count.baseOnHand)
        val blockedDelta = if (lot.quality == LotQuality.DAMAGED) delta else 0L
        balance.onHand = quantity
        balance.blocked = Math.addExact(balance.blocked, blockedDelta)
        count.adjustment = delta
        count.appliedAt = now()
        count.appliedBy = actor()
        count.approvalReason = request.reason
        count.approvalContent = content
        count.status = StockCountStatus.APPLIED
        ledger.save(StockLedger(documentType = "STOCK_COUNT", documentId = id, skuId = count.skuId,
            warehouseId = count.warehouseId, lotId = count.lotId, onHandDelta = delta, blockedDelta = blockedDelta,
            actorId = actor(), reason = request.reason, occurredAt = now()))
        outbox.record(listOf(OutboxEvent(aggregateType = "INVENTORY_COUNT", aggregateId = id,
            eventType = "STOCK_COUNT_APPLIED", payload = mapper.writeValueAsString(mapOf("schemaVersion" to 1, "count" to count)))))
        return count
    }

    fun cancel(id: UUID, @Valid request: StockCountAction): StockCount {
        val count = locked(id)
        val content = mapper.writeValueAsString(request)
        count.cancellationContent?.let { conflict(it == content, "이미 취소한 사유가 다릅니다"); return count }
        conflict(count.status in setOf(StockCountStatus.OPEN, StockCountStatus.MEASURED), "종결된 실사는 취소할 수 없습니다")
        count.cancellationContent = content
        count.cancellationReason = request.reason
        count.canceledAt = now()
        count.canceledBy = actor()
        count.status = StockCountStatus.CANCELED
        return count
    }

    @Transactional(readOnly = true) fun get(id: UUID) = counts.findById(id).orElseThrow { missing("실사") }
    @Transactional(readOnly = true) fun list(warehouseId: UUID?, incompleteOnly: Boolean, page: Int, size: Int) =
        counts.search(warehouseId, incompleteOnly, page(page, size))

    fun reconcile(id: UUID, @Valid request: ReconciliationRequest): ReconciliationResponse {
        val content = mapper.writeValueAsString(request)
        skus.lock(request.skuId) ?: throw missing("SKU")
        reports.findById(id).orElse(null)?.let {
            conflict(it.requestContent == content, "같은 대사 ID의 요청이 다릅니다")
            return response(it)
        }
        request.previousReportId?.let {
            val previous = reports.findById(it).orElseThrow { missing("선행 대사") }
            conflict(previous.skuId == request.skuId && previous.warehouseId == request.warehouseId &&
                previous.pageNumber == request.page && previous.pageSize == request.size, "후속 대사는 선행 대사와 조회 범위가 같아야 합니다")
        }
        // 모든 수량 writer가 얻는 SKU 잠금으로 두 테이블을 같은 커밋 경계에서 비교한다.
        val rows = jdbc.queryForList("""WITH movement AS (
            SELECT warehouse_id,lot_id,sum(on_hand_delta) h,sum(reserved_delta) r,sum(allocated_delta) a,
                sum(blocked_delta) b,sum(transfer_held_delta) t
            FROM inventory_stock_ledger WHERE sku_id=? GROUP BY warehouse_id,lot_id
        ) SELECT COALESCE(s.warehouse_id,m.warehouse_id) AS "warehouseId", COALESCE(s.lot_id,m.lot_id) AS "lotId",
            s.id AS "balanceId", s.version AS "balanceVersion",
            s.on_hand AS "onHand", s.reserved AS "reserved", s.allocated AS "allocated",
            s.blocked AS "blocked", s.transfer_held AS "transferHeld",
            COALESCE(m.h,0) AS "ledgerOnHand",COALESCE(m.r,0) AS "ledgerReserved",COALESCE(m.a,0) AS "ledgerAllocated",
            COALESCE(m.b,0) AS "ledgerBlocked",COALESCE(m.t,0) AS "ledgerTransferHeld",
            (s.id IS NOT NULL AND s.on_hand=COALESCE(m.h,0) AND s.reserved=COALESCE(m.r,0)
                AND s.allocated=COALESCE(m.a,0) AND s.blocked=COALESCE(m.b,0) AND s.transfer_held=COALESCE(m.t,0)) AS matched
            FROM (SELECT * FROM inventory_stock_balance WHERE sku_id=?) s
            FULL OUTER JOIN movement m ON s.warehouse_id=m.warehouse_id AND s.lot_id=m.lot_id
            WHERE (CAST(? AS uuid) IS NULL OR COALESCE(s.warehouse_id,m.warehouse_id)=?)
            ORDER BY COALESCE(s.warehouse_id,m.warehouse_id), COALESCE(s.lot_id,m.lot_id) LIMIT ? OFFSET ?""",
            request.skuId, request.skuId, request.warehouseId, request.warehouseId, request.size + 1,
            Math.multiplyExact(request.page.toLong(), request.size.toLong()))
        val selected = rows.take(request.size)
        val report = StockReconciliation(id, request.skuId, request.warehouseId, request.previousReportId, now(), actor(),
            request.reason, request.page, request.size, rows.size > request.size,
            selected.count { it["matched"] != true }, content, mapper.writeValueAsString(selected))
        reports.insertIfAbsent(id, report.skuId, report.warehouseId, report.previousReportId, report.checkedAt, report.actorId,
            report.reason, report.pageNumber, report.pageSize, report.hasNext, report.mismatchCount, content, report.resultContent)
        val saved = reports.findById(id).orElseThrow()
        conflict(saved.requestContent == content, "같은 대사 ID의 요청이 다릅니다")
        return response(saved)
    }
    @Transactional(readOnly = true) fun report(id: UUID) = response(reports.findById(id).orElseThrow { missing("대사") })
    @Transactional(readOnly = true) fun reports(skuId: UUID, warehouseId: UUID?, page: Int, size: Int) =
        reports.search(skuId, warehouseId, page(page, size))

    private fun response(report: StockReconciliation) = ReconciliationResponse(report, mapper.readTree(report.resultContent))
    private fun locked(id: UUID) = counts.lock(id) ?: throw missing("실사")
    private fun uncommitted(balance: StockBalance) = conflict(balance.reserved == 0L && balance.allocated == 0L && balance.transferHeld == 0L,
        "활성 예약·할당/피킹·이동 확보가 있습니다. 해당 작업 종료 후 실사하세요")
    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)
    private fun actor() = SecurityContextUtil.getCurrentUserId()
    private fun page(page: Int, size: Int): PageRequest {
        require(page >= 0 && size in 1..200) { "page는 0 이상, size는 1~200이어야 합니다" }
        return PageRequest.of(page, size)
    }
    private fun missing(target: String) = ResponseStatusException(HttpStatus.NOT_FOUND, "$target 정보를 찾을 수 없습니다")
    private fun conflict(valid: Boolean, message: String) { if (!valid) throw ResponseStatusException(HttpStatus.CONFLICT, message) }
}
