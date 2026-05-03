package com.eventfulcommerce.settlement.controller

import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.dto.SettlementResponse
import com.eventfulcommerce.settlement.dto.SettlementSummaryResponse
import com.eventfulcommerce.settlement.service.SettlementService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/settlements")
class SettlementController(private val settlementService: SettlementService) {

    // 내 정산 목록 조회 (판매자)
    @GetMapping("/my")
    @PreAuthorize("hasRole('SELLER')")
    fun getMySettlements(
        @RequestParam(required = false) status: SettlementStatus?
    ): ResponseEntity<List<SettlementResponse>> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(settlementService.getMySettlements(sellerId, status))
    }

    // 내 정산 요약 (판매자)
    @GetMapping("/my/summary")
    @PreAuthorize("hasRole('SELLER')")
    fun getMySummary(): ResponseEntity<SettlementSummaryResponse> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(settlementService.getMySummary(sellerId))
    }

    // 전체 정산 목록 (관리자)
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    fun getAllSettlements(
        @RequestParam(required = false) status: SettlementStatus?
    ): ResponseEntity<List<SettlementResponse>> =
        ResponseEntity.ok(settlementService.getAllSettlements(status))

    // 정산 지급 완료 처리 (관리자: CONFIRMED → PAID)
    @PatchMapping("/{settlementId}/pay")
    @PreAuthorize("hasRole('ADMIN')")
    fun pay(@PathVariable settlementId: UUID): ResponseEntity<SettlementResponse> =
        ResponseEntity.ok(settlementService.pay(settlementId))
}
