package com.eventfulcommerce.order.scheduler

import com.eventfulcommerce.common.InventoryStockSnapshotPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.order.repository.ProductReadModelRepository
import com.eventfulcommerce.order.service.InventoryReservationService
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}

/**
 * Redis is the reservation-time source. When no hold is active, its available quantity can
 * safely repair the Product DB projection through the regular Order outbox path.
 */
@Component
class InventoryStockReconciliationScheduler(
    private val productReadModelRepository: ProductReadModelRepository,
    private val inventoryReservationService: InventoryReservationService,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper,
    @Value("\${order.inventory.reconciliation-batch-size:100}")
    private val batchSize: Int
) {
    private var nextPage = 0

    @Scheduled(fixedDelayString = "\${order.inventory.reconciliation-fixed-delay-ms:300000}")
    @Transactional
    fun reconcileAvailableStock() {
        val products = productReadModelRepository.findAll(
            PageRequest.of(nextPage, batchSize.coerceIn(1, MAX_BATCH_SIZE), Sort.by("productId"))
        )
        if (products.isEmpty) {
            nextPage = 0
            return
        }
        nextPage = if (products.hasNext()) nextPage + 1 else 0
        val repairEvents = products.content.mapNotNull { product ->
            val snapshot = inventoryReservationService.getStockSnapshot(product.productId.toString())
            val availableStock = snapshot.availableStock ?: run {
                logger.warn { "Redis inventory key is missing during reconciliation: productId=${product.productId}" }
                return@mapNotNull null
            }
            if (snapshot.heldQuantity != 0L) return@mapNotNull null
            if (availableStock !in 0..Int.MAX_VALUE.toLong()) {
                logger.error { "Redis inventory value is outside Int range: productId=${product.productId}, stock=$availableStock" }
                return@mapNotNull null
            }
            if (product.stock.toLong() == availableStock) return@mapNotNull null

            OutboxEvent(
                aggregateType = "INVENTORY",
                aggregateId = product.productId,
                eventType = INVENTORY_STOCK_SNAPSHOT,
                payload = objectMapper.writeValueAsString(
                    InventoryStockSnapshotPayload(product.productId, availableStock.toInt())
                ),
                status = OutboxStatus.PENDING
            )
        }
        if (repairEvents.isNotEmpty()) {
            outboxEventService.record(repairEvents)
            logger.warn { "Inventory drift reconciliation requested: products=${repairEvents.size}" }
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 1_000
        const val INVENTORY_STOCK_SNAPSHOT = "INVENTORY_STOCK_SNAPSHOT"
    }
}
