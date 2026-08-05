package com.eventfulcommerce.shipping.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

private val completionWorkerLogger = KotlinLogging.logger { }

@Component
class ShippingCompletionWorker(
    private val shippingCompletionService: ShippingCompletionService,
    private val shippingFailureService: ShippingFailureService,
    @Value("\${shipping.completion-delay-ms}") private val completionDelayMs: Long
) {
    @Async("shippingCompletionExecutor")
    fun completeAfterDelay(shippingId: UUID) {
        try {
            Thread.sleep(completionDelayMs)
            shippingCompletionService.complete(shippingId)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            completionWorkerLogger.info { "배송 완료 worker가 중단됨: shippingId=$shippingId" }
        } catch (exception: Exception) {
            completionWorkerLogger.error(exception) { "배송 완료 처리 실패: shippingId=$shippingId" }
            shippingFailureService.fail(shippingId, exception)
        }
    }
}
