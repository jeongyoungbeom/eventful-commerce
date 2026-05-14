package com.eventfulcommerce.order.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * 주문 취소 서비스 (분산락 전담)
 * 
 * 책임:
 * - 분산락 획득/해제
 * - OrderCancelExecutor에게 실제 취소 위임
 */
@Service
class OrderCancelService(
    private val orderCancelExecutor: OrderCancelExecutor,
    private val redissonClient: RedissonClient
) {

    /**
     * 주문 취소 (분산락 적용)
     * 
     * @param orderId 주문 ID
     * @param reason 취소 사유
     * @return 취소 성공 여부
     */
    fun cancel(orderId: UUID, reason: String): Boolean {
        val lockKey = "order:lock:$orderId"
        val lock = redissonClient.getLock(lockKey)
        
        return try {
            // 락 획득 시도 (최대 10초 대기, 30초 후 자동 해제)
            val acquired = lock.tryLock(10, 30, TimeUnit.SECONDS)
            
            if (!acquired) {
                logger.warn { "❌ 주문 취소 락 획득 실패: orderId=$orderId, reason=$reason" }
                return false
            }
            
            try {
                logger.info { "🔒 주문 취소 락 획득: orderId=$orderId, reason=$reason" }

                orderCancelExecutor.execute(orderId, reason)
                
            } finally {
                lock.unlock()
                logger.info { "🔓 주문 취소 락 해제: orderId=$orderId" }
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ 주문 취소 처리 중 에러: orderId=$orderId, reason=$reason" }
            false
        }
    }

    fun cancelSellerOrder(orderId: UUID, sellerOrderId: UUID, reason: String): Boolean {
        val lock = redissonClient.getLock("order:cancel:$orderId")

        return try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                logger.warn { "주문 취소 락 획득 실패: orderId=$orderId" }
                return false
            }
            orderCancelExecutor.executeSellerOrder(orderId, sellerOrderId, reason)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.error(e) { "주문 취소 중 인터럽트 발생: orderId=$orderId" }
            false
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
                logger.info { "주문 취소 락 해제: orderId=$orderId" }
            }
        }
    }
}
