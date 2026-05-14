package com.eventfulcommerce.settlement.controller

import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.dto.SettlementResponse
import com.eventfulcommerce.settlement.dto.SettlementSummaryResponse
import com.eventfulcommerce.settlement.service.SettlementService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/settlements")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Settlements", description = "판매자 정산 조회 및 관리자 지급 처리 API")
class SettlementController(private val settlementService: SettlementService) {

    // 내 정산 목록 조회 (판매자)
    @GetMapping("/my")
    @PreAuthorize("hasRole('SELLER')")
    @Operation(summary = "내 정산 목록 조회", description = "로그인한 판매자의 정산 목록을 조회합니다. status를 지정하면 PENDING, PARTIALLY_REFUNDED, REFUNDED, CONFIRMED, PAID 상태별로 필터링합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "정산 목록 조회 성공"),
        ApiResponse(responseCode = "403", description = "SELLER 권한 필요")
    )
    fun getMySettlements(
        @RequestParam(required = false) status: SettlementStatus?
    ): ResponseEntity<List<SettlementResponse>> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(settlementService.getMySettlements(sellerId, status))
    }

    // 내 정산 요약 (판매자)
    @GetMapping("/my/summary")
    @PreAuthorize("hasRole('SELLER')")
    @Operation(summary = "내 정산 요약 조회", description = "로그인한 판매자의 상태별 정산 건수와 지급 예정/확정/지급 완료 금액을 집계합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "정산 요약 조회 성공"),
        ApiResponse(responseCode = "403", description = "SELLER 권한 필요")
    )
    fun getMySummary(): ResponseEntity<SettlementSummaryResponse> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(settlementService.getMySummary(sellerId))
    }

    // 전체 정산 목록 (관리자)
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "전체 정산 목록 조회", description = "ADMIN 권한으로 모든 판매자의 정산 목록을 조회합니다. status 파라미터로 상태별 조회가 가능합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전체 정산 목록 조회 성공"),
        ApiResponse(responseCode = "403", description = "ADMIN 권한 필요")
    )
    fun getAllSettlements(
        @RequestParam(required = false) status: SettlementStatus?
    ): ResponseEntity<List<SettlementResponse>> =
        ResponseEntity.ok(settlementService.getAllSettlements(status))

    // 정산 지급 완료 처리 (관리자: CONFIRMED → PAID)
    @PatchMapping("/{settlementId}/pay")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "정산 지급 완료 처리", description = "ADMIN 권한으로 CONFIRMED 상태의 정산을 PAID로 변경합니다. Redisson 분산락으로 중복 지급 처리를 방지합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "정산 지급 완료 처리 성공"),
        ApiResponse(responseCode = "400", description = "CONFIRMED가 아닌 정산 또는 동시 처리 중"),
        ApiResponse(responseCode = "403", description = "ADMIN 권한 필요"),
        ApiResponse(responseCode = "404", description = "정산 없음")
    )
    fun pay(@PathVariable settlementId: UUID): ResponseEntity<SettlementResponse> =
        ResponseEntity.ok(settlementService.pay(settlementId))
}
