package com.eventfulcommerce.order.controller

import com.eventfulcommerce.order.service.DistributedLockTestService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/test/lock")
@Tag(name = "Internal Lock Test", description = "Redisson 분산락 수동 검증용 내부 테스트 API")
class LockTestController(
    private val lockTestService: DistributedLockTestService
) {

    /**
     * 기본 락 테스트
     * 
     * GET /test/lock/basic?orderId=uuid&holdTime=5
     */
    @GetMapping("/basic")
    @Operation(summary = "기본 락 테스트", description = "orderId 기준 분산락을 획득하고 holdTime초 동안 점유한 뒤 해제합니다.")
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
    @Operation(summary = "즉시 실패 락 테스트", description = "락 대기 없이 즉시 획득을 시도합니다. 이미 점유 중이면 false를 반환합니다.")
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
    @Operation(summary = "Watch Dog 락 테스트", description = "Redisson Watch Dog가 긴 작업 중 락 TTL을 연장하는지 확인합니다.")
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
