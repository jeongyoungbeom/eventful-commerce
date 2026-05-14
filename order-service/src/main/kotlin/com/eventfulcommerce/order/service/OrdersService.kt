package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.OrderItem
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.domain.entity.SellerOrder
import com.eventfulcommerce.order.domain.entity.SellerOrderStatus
import com.eventfulcommerce.order.dto.FailedOrderItemResponse
import com.eventfulcommerce.order.dto.OrderResponse
import com.eventfulcommerce.order.exception.OrderForbiddenException
import com.eventfulcommerce.order.exception.OrderNotFoundException
import com.eventfulcommerce.order.repository.OrdersRepository
import com.eventfulcommerce.order.repository.ProductReadModelRepository
import com.eventfulcommerce.order.repository.SellerOrderRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class OrdersService(
    private val ordersRepository: OrdersRepository,
    private val sellerOrderRepository: SellerOrderRepository,
    private val productReadModelRepository: ProductReadModelRepository,
    private val outboxEventService: OutboxEventService,
    private val inventoryReservationService: InventoryReservationService,
    private val idempotencyHandler: IdempotencyHandler,
    private val objectMapper: ObjectMapper,
    private val orderCancelService: OrderCancelService,
    @Value("\${order.commission-rate:0.1}") private val commissionRate: Double
) {
    private val ttlSeconds = 10 * 60L

    @Transactional
    fun orders(request: OrdersRequest, userId: UUID): OrderResponse {
        val order = ordersRepository.save(
            Orders(
                userId = userId,
                status = OrdersStatus.ORDER_RESERVED,
                expiresAt = Instant.now().plusSeconds(ttlSeconds)
            )
        )

        val failedItems = mutableListOf<FailedOrderItemResponse>()
        val reservedItems = request.items.mapNotNull { itemRequest ->
            val product = productReadModelRepository.findById(itemRequest.productId).orElse(null)
            if (product == null || product.status != "ACTIVE") {
                failedItems.add(
                    FailedOrderItemResponse(
                        productId = itemRequest.productId,
                        reason = "PRODUCT_NOT_AVAILABLE",
                        requestedQuantity = itemRequest.quantity,
                        availableQuantity = 0
                    )
                )
                return@mapNotNull null
            }

            val reservationId = inventoryReservationService.reserve(
                productId = product.productId.toString(),
                orderId = order.id,
                quantity = itemRequest.quantity,
                ttlSeconds = ttlSeconds
            )

            if (reservationId == null) {
                failedItems.add(
                    FailedOrderItemResponse(
                        productId = itemRequest.productId,
                        reason = "INSUFFICIENT_STOCK",
                        requestedQuantity = itemRequest.quantity,
                        availableQuantity = inventoryReservationService.getAvailableStock(product.productId.toString())
                    )
                )
                null
            } else {
                ReservedItem(
                    productId = product.productId,
                    productName = product.name,
                    sellerId = product.sellerId,
                    quantity = itemRequest.quantity,
                    unitPrice = product.price,
                    totalAmount = product.price * itemRequest.quantity,
                    reservationId = reservationId
                )
            }
        }

        if (reservedItems.isEmpty()) {
            ordersRepository.delete(order)
            logger.info { "주문 생성 생략 - 예약 성공 상품 없음: userId=$userId" }
            return OrderResponse(
                orderId = null,
                totalItemAmount = 0,
                totalDeliveryFee = 0,
                totalPaymentAmount = 0,
                totalCommissionAmount = 0,
                totalSettlementAmount = 0,
                status = OrdersStatus.ORDER_FAILED,
                expiresAt = null,
                sellerOrders = emptyList(),
                failedItems = failedItems,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        }

        reservedItems.groupBy { it.sellerId }.forEach { (sellerId, items) ->
            val itemTotalAmount = items.sumOf { it.totalAmount }
            val deliveryFee = 0L
            val commissionAmount = (itemTotalAmount * commissionRate).toLong()
            val sellerOrder = SellerOrder(
                order = order,
                sellerId = sellerId,
                itemTotalAmount = itemTotalAmount,
                deliveryFee = deliveryFee,
                paymentAmount = itemTotalAmount + deliveryFee,
                commissionRate = commissionRate,
                commissionAmount = commissionAmount,
                settlementAmount = itemTotalAmount - commissionAmount,
                status = SellerOrderStatus.RESERVED
            )

            items.forEach {
                sellerOrder.addItem(
                    OrderItem(
                        sellerOrder = sellerOrder,
                        productId = it.productId,
                        productName = it.productName,
                        quantity = it.quantity,
                        unitPrice = it.unitPrice,
                        totalAmount = it.totalAmount,
                        reservationId = it.reservationId
                    )
                )
            }

            order.addSellerOrder(sellerOrder)
        }

        order.recomputeTotals()
        ordersRepository.save(order)
        recordOrderReserved(order)

        logger.info { "다판매자 주문 생성 완료: orderId=${order.id}, sellerOrders=${order.sellerOrders.size}, failedItems=${failedItems.size}" }
        return OrderResponse.from(order).copy(failedItems = failedItems)
    }

    @Transactional
    fun handlePaymentCompleted(value: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(value.eventId) {
            val payload = objectMapper.readValue(value.payload, PaymentCompletedPayload::class.java)
            val order = ordersRepository.findById(payload.orderId).orElseThrow {
                IllegalStateException("주문을 찾을 수 없습니다: orderId=${payload.orderId}")
            }

            if (order.status == OrdersStatus.ORDER_CONFIRMED) {
                logger.info { "이미 확인된 주문입니다: orderId=${order.id}" }
                return@executeIdempotent
            }
            if (order.status != OrdersStatus.ORDER_RESERVED) {
                logger.error { "잘못된 주문 상태: orderId=${order.id}, status=${order.status}" }
                return@executeIdempotent
            }

            order.sellerOrders.forEach { sellerOrder ->
                sellerOrder.items.forEach { item ->
                    inventoryReservationService.commit(item.productId.toString(), item.reservationId, item.quantity)
                }
                sellerOrder.confirm()
            }
            order.recomputeStatus()
            ordersRepository.save(order)

            val confirmedPayload = OrderConfirmedPayload(
                orderId = order.id,
                userId = order.userId,
                totalAmount = order.totalPaymentAmount,
                sellerOrders = order.sellerOrders.map {
                    OrderConfirmedSellerPayload(
                        sellerOrderId = it.id,
                        sellerId = it.sellerId,
                        paymentAmount = it.paymentAmount
                    )
                },
                confirmedAt = Instant.now()
            )

            outboxEventService.record(
                listOf(
                    OutboxEvent(
                        aggregateType = OrdersStatus.ORDER.toString(),
                        aggregateId = order.id,
                        eventType = OrdersStatus.ORDER_CONFIRMED.toString(),
                        payload = objectMapper.writeValueAsString(confirmedPayload),
                        status = OutboxStatus.PENDING
                    )
                )
            )

            logger.info { "주문 확정 완료: orderId=${order.id}" }
        }
    }

    @Transactional
    fun handlePaymentFailed(value: OutboxEventMessage) {
        idempotencyHandler.executeIdempotent(value.eventId) {
            val payload = objectMapper.readValue(value.payload, PaymentFailedPayload::class.java)
            orderCancelService.cancel(payload.orderId, "결제 실패")
        }
    }

    @Transactional(readOnly = true)
    fun getOrder(orderId: UUID, userId: UUID): Orders {
        val order = ordersRepository.findById(orderId).orElseThrow { OrderNotFoundException(orderId) }
        if (order.userId != userId) throw OrderForbiddenException(orderId)
        return order
    }

    @Transactional(readOnly = true)
    fun getMyOrders(userId: UUID): List<Orders> =
        ordersRepository.findByUserIdOrderByCreatedAtDesc(userId)

    @Transactional(readOnly = true)
    fun getOrdersByUserId(userId: UUID): List<Orders> =
        ordersRepository.findByUserIdOrderByCreatedAtDesc(userId)

    @Transactional(readOnly = true)
    fun getSellerOrders(sellerId: UUID) =
        sellerOrderRepository.findBySellerIdOrderByCreatedAtDesc(sellerId)

    fun cancelOrder(orderId: UUID, userId: UUID): Boolean {
        val order = ordersRepository.findById(orderId).orElse(null)
            ?: throw OrderNotFoundException(orderId)

        if (order.userId != userId) {
            logger.warn { "주문 취소 권한 없음: orderId=$orderId, requestUserId=$userId, ownerUserId=${order.userId}" }
            throw OrderForbiddenException(orderId)
        }

        return orderCancelService.cancel(orderId, "사용자 요청")
    }

    fun cancelSellerOrder(orderId: UUID, sellerOrderId: UUID, userId: UUID): Boolean {
        val order = ordersRepository.findById(orderId).orElse(null)
            ?: throw OrderNotFoundException(orderId)
        if (order.userId != userId) throw OrderForbiddenException(orderId)
        return orderCancelService.cancelSellerOrder(orderId, sellerOrderId, "사용자 요청")
    }

    private fun recordOrderReserved(order: Orders) {
        val payload = OrderReservedPayload(
            orderId = order.id,
            userId = order.userId,
            totalItemAmount = order.totalItemAmount,
            totalDeliveryFee = order.totalDeliveryFee,
            totalPaymentAmount = order.totalPaymentAmount,
            totalCommissionAmount = order.totalCommissionAmount,
            totalSettlementAmount = order.totalSettlementAmount,
            sellerOrders = order.sellerOrders.map { sellerOrder ->
                OrderReservedSellerPayload(
                    sellerOrderId = sellerOrder.id,
                    sellerId = sellerOrder.sellerId,
                    itemTotalAmount = sellerOrder.itemTotalAmount,
                    deliveryFee = sellerOrder.deliveryFee,
                    paymentAmount = sellerOrder.paymentAmount,
                    commissionRate = sellerOrder.commissionRate,
                    commissionAmount = sellerOrder.commissionAmount,
                    settlementAmount = sellerOrder.settlementAmount,
                    items = sellerOrder.items.map { item ->
                        OrderReservedItemPayload(
                            orderItemId = item.id,
                            productId = item.productId,
                            productName = item.productName,
                            quantity = item.quantity,
                            unitPrice = item.unitPrice,
                            totalAmount = item.totalAmount,
                            reservationId = item.reservationId
                        )
                    }
                )
            },
            expiresAt = order.expiresAt,
            createdAt = order.createdAt
        )

        outboxEventService.record(
            listOf(
                OutboxEvent(
                    aggregateType = OrdersStatus.ORDER.toString(),
                    aggregateId = order.id,
                    eventType = OrdersStatus.ORDER_RESERVED.toString(),
                    payload = objectMapper.writeValueAsString(payload),
                    status = OutboxStatus.PENDING
                )
            )
        )
    }
}

private data class ReservedItem(
    val productId: UUID,
    val productName: String,
    val sellerId: UUID,
    val quantity: Int,
    val unitPrice: Long,
    val totalAmount: Long,
    val reservationId: UUID
)
