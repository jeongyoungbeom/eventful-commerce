package com.eventfulcommerce.inventory.controller

import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.service.FulfillmentService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/inventory")
class FulfillmentController(private val service: FulfillmentService) {
    @GetMapping("/outbounds") fun list(@RequestParam orderId: UUID, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int) = service.list(orderId, page, size)
    @GetMapping("/outbounds/{id}") fun get(@PathVariable id: UUID) = service.get(id)
    @PostMapping("/outbounds/{id}/{action:pick|pack|ship}") fun act(@PathVariable id: UUID, @PathVariable action: String,
        @Valid @RequestBody request: OutboundActionRequest) = service.act(id, action, request)
    @PutMapping("/outbounds/{id}/cancellation-decision") fun decide(@PathVariable id: UUID,
        @Valid @RequestBody request: CancellationDecisionRequest) = service.decide(id, request)
    @PutMapping("/outbounds/{id}/ineligibility-cancellation") fun cancelIneligible(@PathVariable id: UUID,
        @Valid @RequestBody request: OutboundActionRequest) = service.cancelIneligible(id, request)
    @PutMapping("/returns/{id}") fun requestReturn(@PathVariable id: UUID, @Valid @RequestBody request: ReturnRequest) = service.requestReturn(id, request)
    @GetMapping("/returns/{id}") fun getReturn(@PathVariable id: UUID) = service.getReturn(id)
    @PostMapping("/returns/{id}/receive") fun receiveReturn(@PathVariable id: UUID) = service.receiveReturn(id)
    @PutMapping("/returns/{id}/inspection") fun inspectReturn(@PathVariable id: UUID,
        @Valid @RequestBody request: ReturnInspectionRequest) = service.inspectReturn(id, request)
}
