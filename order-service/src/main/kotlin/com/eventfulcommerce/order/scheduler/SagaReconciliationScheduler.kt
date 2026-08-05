package com.eventfulcommerce.order.scheduler

import com.eventfulcommerce.order.service.SagaReconciliationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Component
class SagaReconciliationScheduler(
    private val sagaReconciliationService: SagaReconciliationService,
    @Value("\${order.saga-reconciliation.stale-after-seconds:300}")
    private val staleAfterSeconds: Long,
    @Value("\${order.saga-reconciliation.batch-size:100}")
    private val batchSize: Int
) {
    @Scheduled(fixedDelayString = "\${order.saga-reconciliation.fixed-delay-ms:60000}")
    fun reconcileStaleSagas() {
        val staleBefore = Instant.now().minusSeconds(staleAfterSeconds.coerceAtLeast(1))
        sagaReconciliationService.findStaleOrderIds(staleBefore, batchSize).forEach { orderId ->
            runCatching { sagaReconciliationService.reconcile(orderId) }
                .onFailure { exception ->
                    logger.error(exception) { "Saga reconciliation failed: orderId=$orderId" }
                }
        }
    }
}
