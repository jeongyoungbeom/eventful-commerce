package com.eventfulcommerce.order.service

import com.eventfulcommerce.order.domain.entity.OrderSaga
import com.eventfulcommerce.order.domain.entity.OrderSagaStatus
import com.eventfulcommerce.order.repository.OrderSagaRefundReceiptRepository
import com.eventfulcommerce.order.repository.OrderSagaRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class OrderSagaServiceTest {
    @Test
    fun `multiple shipping compensations wait for every distinct seller order refund`() {
        val orderId = UUID.randomUUID()
        val saga = OrderSaga(orderId = orderId, status = OrderSagaStatus.CONFIRMED)
        saga.id = UUID.randomUUID()
        val repository = mockk<OrderSagaRepository>()
        val receiptRepository = mockk<OrderSagaRefundReceiptRepository>()
        every { repository.findByOrderId(orderId) } returns saga
        every { receiptRepository.existsBySagaIdAndSellerOrderId(any(), any()) } returns false
        every { receiptRepository.save(any()) } answers { firstArg() }

        val service = OrderSagaService(repository, receiptRepository)
        service.markRefundPending(orderId, 1)
        service.markRefundPending(orderId, 1)

        assertEquals(OrderSagaStatus.REFUND_PENDING, saga.status)
        assertEquals(2, saga.expectedRefundCount)

        service.markRefundReceived(orderId, UUID.randomUUID())
        assertEquals(OrderSagaStatus.REFUND_PENDING, saga.status)
        assertEquals(1, saga.refundedCount)

        service.markRefundReceived(orderId, UUID.randomUUID())
        assertEquals(OrderSagaStatus.COMPENSATED, saga.status)
        assertEquals(2, saga.refundedCount)
    }

    @Test
    fun `same seller order refund replay is counted only once`() {
        val orderId = UUID.randomUUID()
        val sellerOrderId = UUID.randomUUID()
        val saga = OrderSaga(
            orderId = orderId,
            status = OrderSagaStatus.REFUND_PENDING,
            expectedRefundCount = 2
        )
        saga.id = UUID.randomUUID()
        val repository = mockk<OrderSagaRepository>()
        val receiptRepository = mockk<OrderSagaRefundReceiptRepository>()
        every { repository.findByOrderId(orderId) } returns saga
        every { receiptRepository.existsBySagaIdAndSellerOrderId(saga.id, sellerOrderId) } returnsMany listOf(false, true)
        every { receiptRepository.save(any()) } answers { firstArg() }

        val service = OrderSagaService(repository, receiptRepository)
        service.markRefundReceived(orderId, sellerOrderId)
        service.markRefundReceived(orderId, sellerOrderId)

        assertEquals(1, saga.refundedCount)
        verify(exactly = 1) { receiptRepository.save(any()) }
    }
}
