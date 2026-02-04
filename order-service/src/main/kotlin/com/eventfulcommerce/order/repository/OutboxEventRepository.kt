package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.entity.OutboxEvent
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {
}