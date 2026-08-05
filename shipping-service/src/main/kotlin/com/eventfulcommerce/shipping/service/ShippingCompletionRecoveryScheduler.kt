package com.eventfulcommerce.shipping.service

import com.eventfulcommerce.shipping.domain.ShippingStatus
import com.eventfulcommerce.shipping.repository.ShippingRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

private val shippingRecoveryLogger = KotlinLogging.logger { }

@Component
class ShippingCompletionRecoveryScheduler(
    private val shippingRepository: ShippingRepository,
    private val shippingCompletionService: ShippingCompletionService,
    private val shippingFailureService: ShippingFailureService,
    @Value("\${shipping.completion-delay-ms}") private val completionDelayMs: Long,
    @Value("\${shipping.completion-recovery-batch-size:100}") private val batchSize: Int
) {
    @Scheduled(fixedDelayString = "\${shipping.completion-recovery-fixed-delay-ms:60000}")
    fun recoverInterruptedCompletions() {
        require(completionDelayMs >= 0) { "shipping.completion-delay-ms must not be negative" }
        require(batchSize > 0) { "shipping.completion-recovery-batch-size must be greater than zero" }

        val cutoff = Instant.now().minusMillis(completionDelayMs)
        shippingRepository.findByStatusAndShippedAtBefore(
            status = ShippingStatus.STARTED,
            cutoff = cutoff,
            pageable = PageRequest.of(0, batchSize)
        ).forEach { shipping ->
            runCatching { shippingCompletionService.complete(shipping.id) }
                .onFailure { exception ->
                    shippingRecoveryLogger.error(exception) { "배송 완료 복구 실패: shippingId=${shipping.id}" }
                    shippingFailureService.fail(
                        shipping.id,
                        exception as? Exception ?: IllegalStateException(exception.message, exception)
                    )
                }
        }
    }
}
