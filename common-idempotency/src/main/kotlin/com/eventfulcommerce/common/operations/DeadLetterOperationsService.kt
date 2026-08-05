package com.eventfulcommerce.common.operations

import com.eventfulcommerce.common.DeadLetterEventService
import com.eventfulcommerce.common.api.response.DeadLetterEventResponse
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class DeadLetterOperationsService(
    private val deadLetterEventService: DeadLetterEventService
) {
    @Transactional(readOnly = true)
    fun findPending(limit: Int): List<DeadLetterEventResponse> =
        deadLetterEventService.findPending(limit).map(DeadLetterEventResponse::from)

    fun replay(eventId: UUID): Boolean = deadLetterEventService.replay(eventId)
}
