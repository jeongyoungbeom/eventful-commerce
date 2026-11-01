package com.eventfulcommerce.inventory.controller

import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.service.StockCountService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/inventory")
class StockCountController(private val service: StockCountService) {
    @PutMapping("/counts/{id}") fun start(@PathVariable id: UUID, @Valid @RequestBody request: StockCountRequest) = service.start(id, request)
    @GetMapping("/counts/{id}") fun get(@PathVariable id: UUID) = service.get(id)
    @GetMapping("/counts") fun list(@RequestParam(required = false) warehouseId: UUID?,
        @RequestParam(defaultValue = "false") incompleteOnly: Boolean, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int) = service.list(warehouseId, incompleteOnly, page, size)
    @PutMapping("/counts/{id}/measurement") fun measure(@PathVariable id: UUID, @Valid @RequestBody request: StockCountMeasurement) = service.measure(id, request)
    @PutMapping("/counts/{id}/approval") fun approve(@PathVariable id: UUID, @Valid @RequestBody request: StockCountAction) = service.approve(id, request)
    @PutMapping("/counts/{id}/cancellation") fun cancel(@PathVariable id: UUID, @Valid @RequestBody request: StockCountAction) = service.cancel(id, request)
    @PutMapping("/reconciliations/{id}") fun reconcile(@PathVariable id: UUID, @Valid @RequestBody request: ReconciliationRequest) = service.reconcile(id, request)
    @GetMapping("/reconciliations/{id}") fun report(@PathVariable id: UUID) = service.report(id)
    @GetMapping("/reconciliations") fun reports(@RequestParam skuId: UUID, @RequestParam(required = false) warehouseId: UUID?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int) = service.reports(skuId, warehouseId, page, size)
}
