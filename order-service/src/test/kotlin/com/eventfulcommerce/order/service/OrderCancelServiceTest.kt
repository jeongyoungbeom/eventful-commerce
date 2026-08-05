package com.eventfulcommerce.order.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import java.util.UUID
import java.util.concurrent.TimeUnit

class OrderCancelServiceTest {
    @Test
    fun `full and seller-order cancellation use one order lock`() {
        val orderId = UUID.randomUUID()
        val sellerOrderId = UUID.randomUUID()
        val executor = mockk<OrderCancelExecutor>()
        val redisson = mockk<RedissonClient>()
        val lock = mockk<RLock>()
        every { redisson.getLock("order:cancel:$orderId") } returns lock
        every { lock.tryLock(10L, TimeUnit.SECONDS) } returns true
        every { lock.isHeldByCurrentThread } returns true
        every { lock.unlock() } just Runs
        every { executor.execute(orderId, "full") } returns true
        every { executor.executeSellerOrder(orderId, sellerOrderId, "partial") } returns true

        val service = OrderCancelService(executor, redisson)

        assertEquals(OrderCancellationOutcome.CANCELED, service.cancelForEvent(orderId, "full"))
        assertEquals(
            OrderCancellationOutcome.CANCELED,
            service.cancelSellerOrderForEvent(orderId, sellerOrderId, "partial")
        )

        verify(exactly = 2) { redisson.getLock("order:cancel:$orderId") }
        verify(exactly = 2) { lock.unlock() }
    }

    @Test
    fun `event cancellation reports lock unavailable without executing business cancellation`() {
        val orderId = UUID.randomUUID()
        val executor = mockk<OrderCancelExecutor>()
        val redisson = mockk<RedissonClient>()
        val lock = mockk<RLock>()
        every { redisson.getLock("order:cancel:$orderId") } returns lock
        every { lock.tryLock(10L, TimeUnit.SECONDS) } returns false

        val outcome = OrderCancelService(executor, redisson).cancelForEvent(orderId, "payment failed")

        assertEquals(OrderCancellationOutcome.LOCK_UNAVAILABLE, outcome)
        verify(exactly = 0) { executor.execute(any(), any()) }
    }
}
