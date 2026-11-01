package com.eventfulcommerce.inventory.controller

import com.eventfulcommerce.inventory.repository.*
import org.springframework.data.domain.PageRequest
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/inventory/operations")
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
class InventoryOperationsController(private val reservations: ReservationRepository, private val outbounds: OutboundRepository,
    private val purchases: PurchaseOrderRepository, private val receipts: InboundReceiptRepository) {
    @GetMapping("/reservations") fun reservations(@RequestParam(required = false) warehouseId: UUID?, @RequestParam(required = false) skuId: UUID?,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int) = reservations.operations(warehouseId, skuId, page(page, size))
    @GetMapping("/outbounds") fun outbounds(@RequestParam(required = false) warehouseId: UUID?, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int) = outbounds.operations(warehouseId, page(page, size))
    @GetMapping("/purchase-orders") fun purchases(@RequestParam(required = false) warehouseId: UUID?, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int) = purchases.operations(warehouseId, page(page, size))
    @GetMapping("/receipts") fun receipts(@RequestParam(required = false) warehouseId: UUID?, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int) = receipts.operations(warehouseId, page(page, size))
    private fun page(page: Int, size: Int): PageRequest {
        require(page >= 0 && size in 1..200) { "page는 0 이상, size는 1~200이어야 합니다" }
        return PageRequest.of(page, size)
    }
}
