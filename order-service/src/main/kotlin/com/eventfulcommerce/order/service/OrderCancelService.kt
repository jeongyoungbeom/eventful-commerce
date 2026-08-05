package com.eventfulcommerce.order.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class OrderCancelService(
    private val orderCancelExecutor: OrderCancelExecutor,
    private val redissonClient: RedissonClient
) {
    fun cancel(orderId: UUID, reason: String): Boolean =
        cancelForEvent(orderId, reason) == OrderCancellationOutcome.CANCELED

    fun cancelSellerOrder(orderId: UUID, sellerOrderId: UUID, reason: String): Boolean =
        cancelSellerOrderForEvent(orderId, sellerOrderId, reason) == OrderCancellationOutcome.CANCELED

    /**
     * Kafka 소비 경로는 LOCK_UNAVAILABLE을 성공으로 취급하면 안 된다.
     * 호출자가 예외를 던져 메시지 재시도를 유도할 수 있도록 결과를 구분한다.
     */
    fun cancelForEvent(orderId: UUID, reason: String): OrderCancellationOutcome =
        withOrderLock(orderId, reason) { orderCancelExecutor.execute(orderId, reason) }

    fun cancelSellerOrderForEvent(
        orderId: UUID,
        sellerOrderId: UUID,
        reason: String
    ): OrderCancellationOutcome =
        withOrderLock(orderId, reason) {
            orderCancelExecutor.executeSellerOrder(orderId, sellerOrderId, reason)
        }

    private fun withOrderLock(
        orderId: UUID,
        reason: String,
        action: () -> Boolean
    ): OrderCancellationOutcome {
        val lock = redissonClient.getLock("order:cancel:$orderId")
        return try {
            // leaseTime을 생략해 Redisson watchdog이 긴 취소 작업 동안 lock을 연장한다.
            if (!lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn { "주문 취소 락 획득 실패: orderId=$orderId, reason=$reason" }
                return OrderCancellationOutcome.LOCK_UNAVAILABLE
            }
            try {
                if (action()) OrderCancellationOutcome.CANCELED else OrderCancellationOutcome.ALREADY_CANCELED
            } finally {
                if (lock.isHeldByCurrentThread) lock.unlock()
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Order cancellation was interrupted: orderId=$orderId", exception)
        }
    }

    private companion object {
        const val LOCK_WAIT_SECONDS = 10L
    }
}

enum class OrderCancellationOutcome {
    CANCELED,
    ALREADY_CANCELED,
    LOCK_UNAVAILABLE
}
