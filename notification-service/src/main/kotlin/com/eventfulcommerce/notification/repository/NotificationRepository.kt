package com.eventfulcommerce.notification.repository

import com.eventfulcommerce.notification.domain.entity.Notification
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NotificationRepository : JpaRepository<Notification, UUID> {
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<Notification>
    fun countByUserIdAndIsReadFalse(userId: UUID): Long
}
