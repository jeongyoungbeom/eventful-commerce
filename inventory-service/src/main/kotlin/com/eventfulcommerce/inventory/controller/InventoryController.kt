package com.eventfulcommerce.inventory.controller

import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.service.InventoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/inventory")
@Tag(name = "Inventory", description = "ADMIN 전용 직매입 발주·입고·창고·로트·원장")
class InventoryController(private val service: InventoryService) {
    @PostMapping("/warehouses") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "창고 등록")
    fun warehouse(@Valid @RequestBody request: WarehouseRequest) = service.createWarehouse(request)

    @GetMapping("/warehouses") fun warehouses(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int) = service.warehouses(page, size)

    @PostMapping("/suppliers") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "공급사 등록")
    fun supplier(@Valid @RequestBody request: SupplierRequest) = service.createSupplier(request)

    @GetMapping("/suppliers") fun suppliers(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int) = service.suppliers(page, size)
    @GetMapping("/skus") fun skus(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int) = service.skus(page, size)

    @PutMapping("/purchase-orders/{id}")
    @Operation(summary = "수동 발주", description = "같은 UUID·본문 재시도는 같은 발주를 반환합니다. 다른 내용은 409입니다.")
    fun purchase(@PathVariable id: UUID, @Valid @RequestBody request: PurchaseOrderRequest) = service.createPurchaseOrder(id, request)

    @GetMapping("/purchase-orders/{id}") fun purchase(@PathVariable id: UUID) = service.purchaseOrder(id)

    @PutMapping("/receipts/{id}")
    @Operation(summary = "입고 확정", description = "같은 UUID·본문은 한 번만 반영합니다. 불량·검수 중은 판매불가, 초과 입고는 409입니다.")
    fun receive(@PathVariable id: UUID, @Valid @RequestBody request: InboundRequest) = service.receive(id, request)

    @PutMapping("/inspections/{id}")
    @Operation(summary = "검수 중 입고 품목의 정상·불량 확정", description = "원 입고 품목 전량을 한 번 검수합니다. 수량 합계를 보존하며 새 입고로 집계하지 않습니다.")
    fun inspect(@PathVariable id: UUID, @Valid @RequestBody request: InspectionRequest) = service.inspect(id, request)

    @GetMapping("/inspections/{id}") fun inspection(@PathVariable id: UUID) = service.inspection(id)

    @GetMapping("/receipts/{id}") fun receipt(@PathVariable id: UUID) = service.receipt(id)
    @GetMapping("/stock") fun stock(@RequestParam skuId: UUID, @RequestParam(required = false) warehouseId: UUID?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int) = service.stock(skuId, warehouseId, page, size)
    @GetMapping("/stock/summary") fun stockSummary(@RequestParam skuId: UUID, @RequestParam(required = false) warehouseId: UUID?) = service.stockSummary(skuId, warehouseId)
    @GetMapping("/ledger") fun ledger(@RequestParam skuId: UUID, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int) = service.ledger(skuId, page, size)
}
