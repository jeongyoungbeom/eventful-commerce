package com.eventfulcommerce.order.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class DistributedLockTestService(
    private val redissonClient: RedissonClient
) {

    /**
     * 분산락 기본 테스트
     * 
     * @param orderId 주문 ID
     * @param holdTime 락을 보유할 시간 (초)
     * @return 락 획득 성공 여부
     */
    fun testBasicLock(orderId: UUID, holdTime: Long = 5): Boolean {
        val lockKey = "order:lock:$orderId"
        val lock = redissonClient.getLock(lockKey)
        
        return try {
            // 락 획득 시도 (최대 10초 대기, 30초 후 자동 해제)
            val acquired = lock.tryLock(10, 30, TimeUnit.SECONDS)
            
            if (acquired) {
                logger.info { "✅ 락 획득 성공: $lockKey" }
                
                try {
                    // 비즈니스 로직 시뮬레이션
                    logger.info { "🔧 작업 수행 중... (${holdTime}초)" }
                    Thread.sleep(holdTime * 1000)
                    logger.info { "✅ 작업 완료" }
                    true
                } finally {
                    // 락 해제
                    lock.unlock()
                    logger.info { "🔓 락 해제: $lockKey" }
                }
            } else {
                logger.warn { "❌ 락 획득 실패 (타임아웃): $lockKey" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ 분산락 처리 중 에러: $lockKey" }
            false
        }
    }

    /**
     * 즉시 실패 테스트 (대기 안 함)
     * 
     * @param orderId 주문 ID
     * @param holdTime 락을 보유할 시간 (초)
     * @return 락 획득 성공 여부
     */
    fun testNoWaitLock(orderId: UUID, holdTime: Long = 5): Boolean {
        val lockKey = "order:lock:$orderId"
        val lock = redissonClient.getLock(lockKey)
        
        return try {
            // 대기 없이 즉시 시도 (0초 대기, 30초 후 자동 해제)
            val acquired = lock.tryLock(0, 30, TimeUnit.SECONDS)
            
            if (acquired) {
                logger.info { "✅ 락 즉시 획득 성공: $lockKey" }
                
                try {
                    logger.info { "🔧 작업 수행 중... (${holdTime}초)" }
                    Thread.sleep(holdTime * 1000)
                    logger.info { "✅ 작업 완료" }
                    true
                } finally {
                    lock.unlock()
                    logger.info { "🔓 락 해제: $lockKey" }
                }
            } else {
                logger.warn { "❌ 락 즉시 실패 (이미 사용 중): $lockKey" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ 분산락 처리 중 에러: $lockKey" }
            false
        }
    }

    /**
     * Watch Dog 테스트 (자동 갱신)
     * 
     * leaseTime을 -1로 설정하면 Watch Dog가 자동으로 락 시간을 갱신
     */
    fun testWatchDog(orderId: UUID, holdTime: Int = 35): Boolean {
        val lockKey = "order:lock:$orderId"
        val lock = redissonClient.getLock(lockKey)
        
        return try {
            // leaseTime = -1 → Watch Dog 활성화 (기본 30초마다 자동 갱신)
            val acquired = lock.tryLock(10, -1, TimeUnit.SECONDS)
            
            if (acquired) {
                logger.info { "✅ 락 획득 성공 (Watch Dog 활성화): $lockKey" }
                
                try {
                    logger.info { "🔧 장시간 작업 시작... (${holdTime}초)" }
                    
                    // 긴 작업 시뮬레이션 (Watch Dog가 자동으로 락 갱신)
                    for (i in 1..holdTime) {
                        Thread.sleep(1000)
                        if (i % 10 == 0) {
                            logger.info { "⏳ 진행 중... ${i}초 경과 (Watch Dog가 락 유지 중)" }
                        }
                    }
                    
                    logger.info { "✅ 장시간 작업 완료" }
                    true
                } finally {
                    lock.unlock()
                    logger.info { "🔓 락 해제: $lockKey" }
                }
            } else {
                logger.warn { "❌ 락 획득 실패: $lockKey" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "❌ Watch Dog 테스트 중 에러: $lockKey" }
            false
        }
    }
}
