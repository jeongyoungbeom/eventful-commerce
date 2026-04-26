package com.eventfulcommerce.order.controller

import com.eventfulcommerce.order.service.DistributedLockTestService
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/test/lock")
class LockTestController(
    private val lockTestService: DistributedLockTestService
) {

    /**
     * 기본 락 테스트
     * 
     * GET /test/lock/basic?orderId=uuid&holdTime=5
     */
    @GetMapping("/basic")
    fun testBasicLock(
        @RequestParam orderId: UUID,
        @RequestParam(defaultValue = "5") holdTime: Long
    ): Map<String, Any> {
        val success = lockTestService.testBasicLock(orderId, holdTime)
        
        return mapOf(
            "success" to success,
            "orderId" to orderId,
            "holdTime" to holdTime,
            "message" to if (success) "락 획득 및 해제 성공" else "락 획득 실패"
        )
    }

    /**
     * 즉시 실패 테스트 (대기 안 함)
     * 
     * GET /test/lock/nowait?orderId=uuid&holdTime=10
     */
    @GetMapping("/nowait")
    fun testNoWaitLock(
        @RequestParam orderId: UUID,
        @RequestParam(defaultValue = "10") holdTime: Long
    ): Map<String, Any> {
        val success = lockTestService.testNoWaitLock(orderId, holdTime)
        
        return mapOf(
            "success" to success,
            "orderId" to orderId,
            "holdTime" to holdTime,
            "message" to if (success) "락 즉시 획득 성공" else "락 즉시 실패 (이미 사용 중)"
        )
    }

    /**
     * Watch Dog 테스트
     * 
     * GET /test/lock/watchdog?orderId=uuid&holdTime=35
     */
    @GetMapping("/watchdog")
    fun testWatchDog(
        @RequestParam orderId: UUID,
        @RequestParam(defaultValue = "35") holdTime: Int
    ): Map<String, Any> {
        val success = lockTestService.testWatchDog(orderId, holdTime)
        
        return mapOf(
            "success" to success,
            "orderId" to orderId,
            "holdTime" to holdTime,
            "message" to if (success) "Watch Dog 테스트 성공" else "Watch Dog 테스트 실패"
        )
    }
}
