package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.entity.OrderSaga
import com.eventfulcommerce.order.domain.entity.OrderSagaStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface OrderSagaRepository : JpaRepository<OrderSaga, UUID> {
    fun findByOrderId(orderId: UUID): OrderSaga?
    fun countByStatus(status: OrderSagaStatus): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select saga from OrderSaga saga where saga.orderId = :orderId")
    fun findByOrderIdForUpdate(@Param("orderId") orderId: UUID): OrderSaga?

    @Query(
        """
        select saga from OrderSaga saga
        where saga.status in :statuses
          and (saga.lastReconciledAt is null or saga.lastReconciledAt < :staleBefore)
        order by saga.updatedAt asc
        """
    )
    fun findReconciliationTargets(
        @Param("statuses") statuses: Collection<OrderSagaStatus>,
        @Param("staleBefore") staleBefore: Instant,
        pageable: Pageable
    ): List<OrderSaga>
}
