package com.eventfulcommerce.payment.repository

import com.eventfulcommerce.payment.domain.entity.Payment
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.*

interface PaymentRepository : JpaRepository<Payment, UUID> {
    fun findByOrderId(orderId: UUID): Payment?
    fun findByPgTxId(pgTxId: String): Payment?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    fun findByOrderIdForUpdate(orderId: UUID): Payment?
}
