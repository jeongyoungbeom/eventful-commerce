package com.eventfulcommerce.common.repository

import com.eventfulcommerce.common.DeadLetterEvent
import com.eventfulcommerce.common.DeadLetterEventStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface DeadLetterEventRepository : JpaRepository<DeadLetterEvent, UUID> {
    fun existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(
        originalTopic: String,
        originalPartition: Int,
        originalOffset: Long
    ): Boolean

    fun countByStatus(status: DeadLetterEventStatus): Long

    fun findByStatusOrderByReceivedAtAsc(status: DeadLetterEventStatus, pageable: Pageable): List<DeadLetterEvent>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from DeadLetterEvent event where event.id = :eventId")
    fun findByIdForUpdate(@Param("eventId") eventId: UUID): DeadLetterEvent?
}
