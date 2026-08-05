package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.entity.OrderSagaRefundReceipt
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OrderSagaRefundReceiptRepository : JpaRepository<OrderSagaRefundReceipt, UUID> {
    fun existsBySagaIdAndSellerOrderId(sagaId: UUID, sellerOrderId: UUID): Boolean
}
