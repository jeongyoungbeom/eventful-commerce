package com.eventfulcommerce.common.api

import com.eventfulcommerce.common.api.response.OutboxFailedEventsResponse
import com.eventfulcommerce.common.api.response.OutboxRequeueResponse
import com.eventfulcommerce.common.operations.OutboxOperationsService
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.UUID

@Validated
@RestController
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "outbox.operations", name = ["enabled"], havingValue = "true")
@RequestMapping("/internal/outbox")
class OutboxOperationsController(
    private val outboxOperationsService: OutboxOperationsService,
    @Value("\${outbox.operations.token:}") private val operationsToken: String
) {
    @GetMapping("/failed")
    fun failed(
        @RequestHeader("X-Outbox-Operations-Token", required = false) requestToken: String?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int
    ): OutboxFailedEventsResponse {
        authorize(requestToken)
        return OutboxFailedEventsResponse(outboxOperationsService.findFailed(limit))
    }

    @PostMapping("/failed/{eventId}/requeue")
    fun requeue(
        @RequestHeader("X-Outbox-Operations-Token", required = false) requestToken: String?,
        @PathVariable eventId: UUID
    ): OutboxRequeueResponse {
        authorize(requestToken)
        val result = outboxOperationsService.requeue(eventId)
        return OutboxRequeueResponse(
            eventId = result.eventId,
            requeued = result.requeued,
            processedAt = result.processedAt
        )
    }

    private fun authorize(requestToken: String?): Unit {
        if (operationsToken.isBlank() || requestToken == null || !MessageDigest.isEqual(
                operationsToken.toByteArray(),
                requestToken.toByteArray()
            )
        ) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Outbox operations token is invalid")
        }
    }
}
