package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID


interface OrdersRepository : JpaRepository<Orders, UUID> {
    fun findByStatusAndExpiresAtBefore(
        status: OrdersStatus,
        expiresAt: Instant
    ): List<Orders>
}