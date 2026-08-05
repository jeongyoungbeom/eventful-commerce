package com.eventfulcommerce.notification.repository

import com.eventfulcommerce.notification.domain.NotificationDeliveryStatus
import com.eventfulcommerce.notification.domain.entity.Notification
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface NotificationRepository : JpaRepository<Notification, UUID> {
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<Notification>
    fun countByUserIdAndIsReadFalse(userId: UUID): Long

    fun findTop100ByDeliveryStatusInAndNextDeliveryAttemptAtLessThanEqualOrderByCreatedAtAsc(
        statuses: Collection<NotificationDeliveryStatus>,
        now: Instant
    ): List<Notification>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notification n where n.id = :id")
    fun findByIdForDelivery(@Param("id") id: UUID): Notification?
}
