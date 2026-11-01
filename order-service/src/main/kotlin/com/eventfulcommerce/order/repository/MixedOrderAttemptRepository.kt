package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.entity.MixedOrderAttempt
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface MixedOrderAttemptRepository : JpaRepository<MixedOrderAttempt, UUID> {
    @Query("select a.id from MixedOrderAttempt a where a.nextRecoveryAt <= :now order by a.nextRecoveryAt, a.id")
    fun findRecoverable(now: Instant, pageable: Pageable): List<UUID>
}
