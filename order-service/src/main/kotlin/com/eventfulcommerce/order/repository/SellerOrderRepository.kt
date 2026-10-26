package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.entity.SellerOrder
import org.springframework.data.domain.Pageable
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface SellerOrderRepository : JpaRepository<SellerOrder, UUID> {
    @Query(
        """
        select distinct so
        from SellerOrder so
        left join fetch so.items
        where so.sellerId = :sellerId
        order by so.createdAt desc
        """
    )
    fun findBySellerIdWithItemsOrderByCreatedAtDesc(@Param("sellerId") sellerId: UUID): List<SellerOrder>

    @Query("select s.order.id from SellerOrder s where s.cancellationStatus = com.eventfulcommerce.order.domain.entity.CancellationStatus.PENDING and s.cancellationRequestedAt <= :cutoff order by s.cancellationRequestedAt, s.id")
    fun findPendingCancellationOrderIds(@Param("cutoff") cutoff: Instant,
        pageable: Pageable): List<UUID>
}
