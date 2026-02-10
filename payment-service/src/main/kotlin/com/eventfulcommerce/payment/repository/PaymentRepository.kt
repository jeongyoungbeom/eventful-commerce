package com.eventfulcommerce.payment.repository

import com.eventfulcommerce.payment.domain.entity.Payment
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface PaymentRepository : JpaRepository<Payment, UUID> {
    fun findByOrderId(orderId: UUID): Payment?
}