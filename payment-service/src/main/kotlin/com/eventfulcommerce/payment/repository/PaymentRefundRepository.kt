package com.eventfulcommerce.payment.repository

import com.eventfulcommerce.payment.domain.entity.PaymentRefund
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PaymentRefundRepository : JpaRepository<PaymentRefund, UUID> {
    fun findByPaymentIdAndSellerOrderId(paymentId: UUID, sellerOrderId: UUID): PaymentRefund?
}
