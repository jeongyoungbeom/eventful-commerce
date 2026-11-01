package com.eventfulcommerce.inventory.controller

import com.eventfulcommerce.inventory.dto.ReservationRequest
import com.eventfulcommerce.inventory.service.ReservationService
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/internal/reservations")
@SecurityRequirement(name = "inventoryServiceToken")
class ReservationController(private val service: ReservationService) {
    @PutMapping("/{id}")
    fun reserve(@PathVariable id: UUID, @Valid @RequestBody request: ReservationRequest) = service.reserve(id, request)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = service.get(id)

    @PostMapping("/{id}/release")
    fun release(@PathVariable id: UUID, @Valid @RequestBody request: ReservationRequest) = service.release(id, request)

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: UUID, @Valid @RequestBody request: ReservationRequest) = service.cancel(id, request)

    @PostMapping("/{id}/allocate")
    fun allocate(@PathVariable id: UUID, @Valid @RequestBody request: ReservationRequest) = service.allocate(id, request)
}
