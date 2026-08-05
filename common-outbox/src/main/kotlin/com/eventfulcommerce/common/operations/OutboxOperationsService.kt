package com.eventfulcommerce.common.operations

import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.api.response.OutboxFailedEventResponse
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class OutboxOperationsService(
    private val outboxEventService: OutboxEventService
) {
    @Transactional(readOnly = true)
    fun findFailed(limit: Int): List<OutboxFailedEventResponse> =
        outboxEventService.findFailed(limit).map(OutboxFailedEventResponse::from)

    fun requeue(id: UUID): OutboxRequeueResult = OutboxRequeueResult(
        eventId = id,
        requeued = outboxEventService.requeueFailed(id),
        processedAt = Instant.now()
    )
}

data class OutboxRequeueResult(
    val eventId: UUID,
    val requeued: Boolean,
    val processedAt: Instant
)
