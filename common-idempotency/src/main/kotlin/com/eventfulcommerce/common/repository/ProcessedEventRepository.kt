package com.eventfulcommerce.common.repository

import com.eventfulcommerce.common.ProcessedEvent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ProcessedEventRepository : JpaRepository<ProcessedEvent, UUID> {
    /**
     * PostgreSQL에서 중복 충돌을 예외로 만들지 않고 즉시 선점 결과를 반환한다.
     * 1이면 최초 처리, 0이면 다른 트랜잭션이 이미 처리했거나 처리 중인 이벤트다.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO processed_event (event_id, processed_at)
            VALUES (:eventId, CURRENT_TIMESTAMP)
            ON CONFLICT (event_id) DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertIfAbsent(@Param("eventId") eventId: UUID): Int

    @Modifying
    @Query("delete from ProcessedEvent event where event.processedAt < :cutoff")
    fun deleteProcessedBefore(@Param("cutoff") cutoff: Instant): Int
}
