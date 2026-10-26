package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.Orders
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID


interface OrdersRepository : JpaRepository<Orders, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Orders o where o.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): Orders?

    fun findByStatusInAndExpiresAtBefore(
        statuses: Collection<OrdersStatus>,
        expiresAt: Instant
    ): List<Orders>

    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<Orders>
}
