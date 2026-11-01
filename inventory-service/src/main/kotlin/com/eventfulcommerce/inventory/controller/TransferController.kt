package com.eventfulcommerce.inventory.controller

import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.service.TransferService
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/inventory/transfers")
@Tag(name = "Transfers", description = "ADMIN 전용 창고 이동·출발·부분 도착·미도착 조회")
class TransferController(private val service: TransferService) {
    @PutMapping("/{id}") fun create(@PathVariable id: UUID, @Valid @RequestBody request: TransferRequest) = service.create(id, request)
    @GetMapping("/{id}") fun get(@PathVariable id: UUID) = service.get(id)
    @GetMapping fun list(@RequestParam(required = false) warehouseId: UUID?,
        @RequestParam(defaultValue = "false") incompleteOnly: Boolean, @RequestParam(defaultValue = "false") overdueOnly: Boolean,
        @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "50") size: Int) =
        service.list(warehouseId, incompleteOnly, overdueOnly, page, size)
    @PutMapping("/{id}/dispatch") fun dispatch(@PathVariable id: UUID, @Valid @RequestBody request: TransferActionRequest) = service.dispatch(id, request)
    @PutMapping("/{id}/cancellation") fun cancel(@PathVariable id: UUID, @Valid @RequestBody request: TransferActionRequest) = service.cancel(id, request)
    @PutMapping("/{id}/receipts/{receiptId}") fun arrive(@PathVariable id: UUID, @PathVariable receiptId: UUID,
        @Valid @RequestBody request: TransferArrivalRequest) = service.arrive(id, receiptId, request)
    @GetMapping("/{id}/receipts") fun receipts(@PathVariable id: UUID, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int) = service.receipts(id, page, size)
    @GetMapping("/{id}/receipts/{receiptId}") fun receipt(@PathVariable id: UUID, @PathVariable receiptId: UUID) = service.receipt(id, receiptId)
}
