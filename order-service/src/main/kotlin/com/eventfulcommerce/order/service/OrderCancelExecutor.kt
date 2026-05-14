package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.SellerOrder
import com.eventfulcommerce.order.domain.entity.SellerOrderStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.eventfulcommerce.order.repository.SellerOrderRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class OrderCancelExecutor(
    private val ordersRepository: OrdersRepository,
    private val sellerOrderRepository: SellerOrderRepository,
    private val inventoryReservationService: InventoryReservationService,
    private val outboxEventService: OutboxEventService,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun execute(orderId: UUID, reason: String): Boolean {
        val order = ordersRepository.findById(orderId).orElse(null) ?: run {
            logger.warn { "주문을 찾을 수 없음: orderId=$orderId" }
            return false
        }

        val targets = order.sellerOrders.filter { it.status != SellerOrderStatus.CANCELED }
        if (targets.isEmpty()) {
            logger.info { "취소 가능한 판매자 주문 없음: orderId=$orderId" }
            return false
        }

        cancelTargets(orderId, targets, reason)
        order.recomputeStatus()
        ordersRepository.save(order)
        recordCanceled(orderId, order.userId, targets, reason)

        logger.info { "주문 취소 완료: orderId=$orderId, reason=$reason" }
        return true
    }

    @Transactional
    fun executeSellerOrder(orderId: UUID, sellerOrderId: UUID, reason: String): Boolean {
        val order = ordersRepository.findById(orderId).orElse(null) ?: run {
            logger.warn { "주문을 찾을 수 없음: orderId=$orderId" }
            return false
        }
        val sellerOrder = sellerOrderRepository.findById(sellerOrderId).orElse(null) ?: run {
            logger.warn { "판매자 주문을 찾을 수 없음: sellerOrderId=$sellerOrderId" }
            return false
        }
        if (sellerOrder.order.id != orderId || sellerOrder.status == SellerOrderStatus.CANCELED) {
            logger.info { "취소 불가능한 판매자 주문: sellerOrderId=$sellerOrderId, status=${sellerOrder.status}" }
            return false
        }

        cancelTargets(orderId, listOf(sellerOrder), reason)
        order.recomputeStatus()
        ordersRepository.save(order)
        recordCanceled(orderId, order.userId, listOf(sellerOrder), reason)

        logger.info { "판매자 주문 취소 완료: orderId=$orderId, sellerOrderId=$sellerOrderId, reason=$reason" }
        return true
    }

    private fun cancelTargets(orderId: UUID, targets: List<SellerOrder>, reason: String) {
        targets.forEach { sellerOrder ->
            when (sellerOrder.status) {
                SellerOrderStatus.RESERVED -> {
                    sellerOrder.items.forEach { item ->
                        inventoryReservationService.release(item.productId.toString(), item.reservationId, item.quantity)
                    }
                }
                SellerOrderStatus.CONFIRMED -> {
                    sellerOrder.items.forEach { item ->
                        inventoryReservationService.adjustStock(item.productId.toString(), item.quantity)
                    }
                }
                SellerOrderStatus.CANCELED -> Unit
            }
            sellerOrder.cancel()
        }
        logger.info { "취소 대상 처리 완료: orderId=$orderId, count=${targets.size}, reason=$reason" }
    }

    private fun recordCanceled(orderId: UUID, userId: UUID, targets: List<SellerOrder>, reason: String) {
        val payload = OrderCanceledPayload(
            orderId = orderId,
            userId = userId,
            reason = reason,
            canceledSellerOrders = targets.map { sellerOrder ->
                OrderCanceledSellerPayload(
                    sellerOrderId = sellerOrder.id,
                    sellerId = sellerOrder.sellerId,
                    refundAmount = sellerOrder.paymentAmount,
                    items = sellerOrder.items.map {
                        OrderCanceledItemPayload(
                            orderItemId = it.id,
                            productId = it.productId,
                            quantity = it.quantity,
                            amount = it.totalAmount
                        )
                    }
                )
            }
        )

        outboxEventService.record(
            listOf(
                OutboxEvent(
                    aggregateType = OrdersStatus.ORDER_CANCELED.toString(),
                    aggregateId = orderId,
                    eventType = "ORDER_CANCELED",
                    payload = objectMapper.writeValueAsString(payload),
                    status = OutboxStatus.PENDING
                )
            )
        )
    }
}
