package com.eventfulcommerce.common.api

import com.eventfulcommerce.common.api.response.DeadLetterEventListResponse
import com.eventfulcommerce.common.api.response.DeadLetterReplayResponse
import com.eventfulcommerce.common.operations.DeadLetterOperationsService
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.UUID

@RestController
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "dlt.operations", name = ["enabled"], havingValue = "true")
@RequestMapping("/internal/dlt")
class DeadLetterOperationsController(
    private val deadLetterOperationsService: DeadLetterOperationsService,
    @Value("\${dlt.operations.token:}") private val operationsToken: String
) {
    @GetMapping
    fun pending(
        @RequestHeader("X-Dlt-Operations-Token", required = false) requestToken: String?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int
    ): DeadLetterEventListResponse {
        authorize(requestToken)
        return DeadLetterEventListResponse(deadLetterOperationsService.findPending(limit))
    }

    @PostMapping("/{eventId}/replay")
    fun replay(
        @RequestHeader("X-Dlt-Operations-Token", required = false) requestToken: String?,
        @PathVariable eventId: UUID
    ): DeadLetterReplayResponse {
        authorize(requestToken)
        return DeadLetterReplayResponse(eventId, deadLetterOperationsService.replay(eventId))
    }

    private fun authorize(requestToken: String?) {
        if (operationsToken.isBlank() || requestToken == null || !MessageDigest.isEqual(
                operationsToken.toByteArray(),
                requestToken.toByteArray()
            )
        ) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "DLT operations token is invalid")
        }
    }
}
