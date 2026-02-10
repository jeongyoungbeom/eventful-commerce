package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.OrderReservedPayload
import com.eventfulcommerce.common.OutboxEvent
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.OutboxEventService
import com.eventfulcommerce.common.OutboxStatus
import com.eventfulcommerce.common.PaymentCompletedPayload
import com.eventfulcommerce.common.ProcessedEvent
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.common.OrderConfirmedPayload
import com.eventfulcommerce.common.PaymentFailedPayload
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.collections.map
import kotlin.math.log

private val logger = KotlinLogging.logger {}

@Service
class OrdersService(
    private val ordersRepository: OrdersRepository,
    private val outboxEventService: OutboxEventService,
    private val inventoryReservationService: InventoryReservationService,
    private val processedEventRepository: ProcessedEventRepository,
    private val objectMapper: ObjectMapper
) {
    private val ttlSeconds = 10 * 60L

    @Transactional
    fun orders(ordersRequests: List<OrdersRequest>): List<String> {
        val orderList = ordersRequests.map { it.toEntity() }
        val saveOrders = ordersRepository.saveAll(orderList)

        saveOrders.mapNotNull { order ->
            val reservationId = inventoryReservationService.reserve(order.id, ttlSeconds)
                ?: run {
                    order.status = OrdersStatus.ORDER_CANCELED
                    throw RuntimeException("재고가 없습니다.")
                }

            order.status = OrdersStatus.ORDER_RESERVED
            order.reservationId = reservationId
            order.expiresAt = Instant.now().plusSeconds(ttlSeconds)
            order
        }
        logger.info { "오더 저장." }
        ordersRepository.saveAll(saveOrders)

        val events = saveOrders.map {
            val payloadJson = objectMapper.writeValueAsString(
                OrderReservedPayload(
                    orderId = it.id,
                    userId = it.userId,
                    totalAmount = it.totalAmount,
                    reservationId = it.reservationId!!,
                    expiresAt = it.expiresAt,
                    createdAt = it.createdAt,
                )
            )

            OutboxEvent(
                aggregateType = OrdersStatus.ORDER.toString(),
                aggregateId = it.id,
                eventType = OrdersStatus.ORDER_RESERVED.toString(),
                payload = payloadJson,
                status = OutboxStatus.PENDING
            )
        }

        logger.info { "오더 -> 결제로 전송" }
        inventoryReservationService.getStockSummary()
        outboxEventService.recode(events)

        return saveOrders.map { it.id.toString() }
    }

    fun handlePaymentCompleted(value: OutboxEventMessage) {
        val eventId = value.eventId

        try {
            processedEventRepository.save(ProcessedEvent(eventId))
        } catch (e: Exception) {
            return
        }

        val readValue = objectMapper.readValue(value.payload, PaymentCompletedPayload::class.java)
        val order = ordersRepository.findById(readValue.orderId).orElseThrow()

        if (order.status == OrdersStatus.ORDER_CONFIRMED) return
        if (order.status != OrdersStatus.ORDER_RESERVED) return
        val reservationId = order.reservationId ?: return
        logger.info { "결제 완료 후 재고 1개 삭제" }
        inventoryReservationService.commit(reservationId)


        val confirmedPayload = OrderConfirmedPayload(
            orderId = order.id,
            userId = order.userId,
            totalAmount = order.totalAmount,
            confirmedAt = Instant.now()
        )

        val outboxEvent = OutboxEvent(
            aggregateType = OrdersStatus.ORDER.toString(),
            aggregateId = order.id,
            eventType = OrdersStatus.ORDER_CONFIRMED.toString(),
            payload = objectMapper.writeValueAsString(confirmedPayload),
            status = OutboxStatus.PENDING
        )
        logger.info { "재고 처리 후 -> 배송으로 전송" }
        outboxEventService.recode(listOf(outboxEvent))
    }

    fun handlePaymentFailed(value: OutboxEventMessage) {
        val eventId = value.eventId
        try {
            processedEventRepository.save(ProcessedEvent(eventId))
        } catch (e: Exception) {
            return
       }

        val readValue = objectMapper.readValue(value.payload, PaymentFailedPayload::class.java)
        val order = ordersRepository.findById(readValue.orderId).orElseThrow()

        if (order.status != OrdersStatus.ORDER_RESERVED) return

        val reservationId = order.reservationId ?: return
        inventoryReservationService.release(reservationId)

        order.status = OrdersStatus.ORDER_CANCELED
        ordersRepository.save(order)
    }
}