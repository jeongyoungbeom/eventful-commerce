package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.*
import com.eventfulcommerce.order.dto.*
import com.eventfulcommerce.order.exception.*
import com.eventfulcommerce.order.repository.*
import com.eventfulcommerce.order.request.*
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// 모든 변경은 주문 행을 먼저 잠그고 로컬 DB만 처리한다. 원격 호출은 MixedOrdersService가 수행한다.
@Service
@Transactional(rollbackFor = [Exception::class])
class MixedOrderStateService(
    private val orders: OrdersRepository,
    private val attempts: MixedOrderAttemptRepository,
    private val claims: OrderRequestIdempotencyRepository,
    private val products: ProductReadModelRepository,
    private val mapper: ObjectMapper,
    private val outbox: OutboxEventService,
    private val clock: java.time.Clock,
    @Value("\${order.commission-rate:0.1}") private val commissionRate: Double,
    @Value("\${order.idempotency.retention-seconds:86400}") private val retentionSeconds: Long,
    @Value("\${order.direct-inventory.delivery-fee:0}") private val deliveryFee: Long,
    @Value("\${order.direct-inventory.free-delivery-threshold:9223372036854775807}") private val freeDeliveryThreshold: Long
) {
    init {
        require(commissionRate.isFinite() && commissionRate in 0.0..1.0)
        require(retentionSeconds > 0 && deliveryFee >= 0 && freeDeliveryThreshold >= 0)
    }

    fun prepare(request: OrdersRequest, userId: UUID, key: String): UUID? {
        val normalized = key.trim()
        require(normalized.isNotEmpty() && normalized.length <= 128) { "Idempotency-Key는 1~128자여야 합니다" }
        val hash = sha256(mapper.writeValueAsString(request))
        val fingerprint = sha256(normalized).take(12)
        if (!claims.tryAcquireClaimLock("$userId:$normalized")) throw OrderIdempotencyInProgressException(fingerprint)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val existing = claims.findByUserIdAndIdempotencyKey(userId, normalized)
        if (existing != null && existing.expiresAt > now) {
            if (existing.requestHash != hash) throw OrderIdempotencyConflictException(fingerprint)
            return existing.orderId?.takeIf(attempts::existsById)
        }
        val productMap = products.findAllById(request.items.map { it.productId }).associateBy { it.productId }
        if (productMap.values.none { it.salesType == SalesType.DIRECT_PURCHASE }) return null
        require(request.items.size in 1..20 && request.items.all { it.quantity > 0 }) { "혼합 주문은 양수 수량의 1~20품목이어야 합니다" }
        require(request.items.map { it.productId }.distinct().size == request.items.size) { "같은 상품은 한 품목으로 요청하세요" }
        val groupIds = productMap.values.map { it.sellerId }.distinct().associateWith { UUID.randomUUID() }
        val items = request.items.map { input ->
            val p = productMap[input.productId]
            require(p == null || p.price >= 0) { "상품 가격이 잘못되었습니다" }
            AttemptItem(UUID.randomUUID(), UUID.randomUUID(), groupIds[p?.sellerId] ?: UUID.randomUUID(),
                input.productId, p?.name ?: "", p?.sellerId ?: UUID(0, 0), p?.salesType ?: SalesType.MARKETPLACE,
                input.quantity, p?.price ?: 0,
                if (p?.status == "ACTIVE") AttemptItemState.PENDING else AttemptItemState.REJECTED,
                if (p?.status == "ACTIVE") null else "PRODUCT_NOT_AVAILABLE")
        }
        val amount = items.fold(0L) { total, item -> Math.addExact(total, item.totalAmount) }
        Math.addExact(amount, deliveryFee)
        claims.deleteExpiredByUserIdAndIdempotencyKey(userId, normalized, now)
        val claimId = UUID.nameUUIDFromBytes("$userId:$normalized".toByteArray(UTF_8))
        check(claims.insertIfAbsent(claimId, userId, normalized, hash, now.plusSeconds(retentionSeconds)) == 1)
        val order = orders.saveAndFlush(Orders(userId, status = OrdersStatus.ORDER_PROCESSING, expiresAt = now.plusSeconds(600), mixedOrder = true))
        val attempt = attempts.save(MixedOrderAttempt(order.id, mapper.writeValueAsString(items), requireNotNull(order.expiresAt),
            deliveryFee, freeDeliveryThreshold, commissionRate))
        refresh(order, attempt, items)
        check(claims.complete(claimId, mapper.writeValueAsString(response(order, attempt, items)), order.id) == 1)
        return order.id
    }

    @Transactional(readOnly = true)
    fun contains(id: UUID) = attempts.existsById(id)

    fun snapshot(id: UUID, userId: UUID? = null): AttemptSnapshot {
        val order = lockedOrder(id, userId)
        val attempt = attempts.findById(id).orElseThrow { OrderNotFoundException(id) }
        val items = items(attempt)
        refresh(order, attempt, items)
        return AttemptSnapshot(id, attempt.expiresAt, items, response(order, attempt, items), attempt.recoveryCursor)
    }

    fun advanceRecovery(id: UUID, itemId: UUID) {
        lockedOrder(id)
        val attempt = attempts.findById(id).orElseThrow()
        val items = items(attempt)
        val index = items.indexOfFirst { it.orderItemId == itemId }
        check(index >= 0)
        // 원격 호출 전에 커밋해 응답 유실·재시작에도 뒤쪽 품목부터 진행한다.
        attempt.recoveryCursor = (index + 1) % items.size
    }

    fun completePass(id: UUID, userId: UUID? = null): OrderResponse {
        val response = snapshot(id, userId).response
        val attempt = attempts.findById(id).orElseThrow()
        if (attempt.nextRecoveryAt != null) attempt.nextRecoveryAt = clock.instant().plusSeconds(5)
        return response
    }

    fun applyResult(id: UUID, itemId: UUID, result: RemoteReservation?) {
        val order = lockedOrder(id)
        val attempt = attempts.findById(id).orElseThrow()
        val items = items(attempt)
        val item = items.single { it.orderItemId == itemId }
        if (order.status in TERMINAL || item.state !in setOf(AttemptItemState.PENDING, AttemptItemState.RESERVED)) return
        if (attempt.paymentState != MixedPaymentState.NONE) return
        val old = item.copy()
        when (result?.state) {
            "RESERVED" -> {
                item.state = AttemptItemState.RESERVED
                item.reason = null
                item.warehouseId = result.warehouseId
                stockAdjustment(order, item, true)
            }
            "REJECTED", "RELEASED", "EXPIRED", "INVALIDATED", "CANCELED" -> {
                item.state = AttemptItemState.REJECTED
                item.reason = result.reason ?: "RESERVATION_${result.state}"
                stockAdjustment(order, item, false)
            }
            else -> { item.state = AttemptItemState.PENDING; item.reason = "RESULT_UNCONFIRMED" }
        }
        if (old != item) changed(attempt)
        refresh(order, attempt, items)
    }

    fun applyRelease(id: UUID, itemId: UUID, released: Boolean) {
        val order = lockedOrder(id)
        val attempt = attempts.findById(id).orElseThrow()
        val items = items(attempt)
        val item = items.single { it.orderItemId == itemId }
        if (released && item.state == AttemptItemState.RELEASE_PENDING) {
            stockAdjustment(order, item, false)
            item.state = AttemptItemState.RELEASED
        }
        refresh(order, attempt, items)
    }

    fun confirm(id: UUID, userId: UUID, request: OrderConfirmationRequest): OrderResponse {
        val order = lockedOrder(id, userId)
        val attempt = attempts.findById(id).orElseThrow()
        val items = items(attempt)
        refresh(order, attempt, items)
        if (order.status !in setOf(OrdersStatus.ORDER_AWAITING_CONFIRMATION, OrdersStatus.ORDER_AWAITING_PAYMENT) ||
            request.compositionVersion != attempt.compositionVersion || request.totalPaymentAmount != order.totalPaymentAmount ||
            items.any { it.state == AttemptItemState.PENDING } || items.none { it.state == AttemptItemState.RESERVED }) {
            throw OrderCompositionConflictException()
        }
        attempt.confirmedVersion = attempt.compositionVersion
        order.status = OrdersStatus.ORDER_AWAITING_PAYMENT
        if (attempt.paymentState == MixedPaymentState.NONE) publishPayment(order, attempt, items)
        return response(order, attempt, items)
    }

    fun paymentCompleted(payload: PaymentCompletedPayload) {
        val order = lockedOrder(payload.orderId)
        val attempt = attempts.findById(order.id).orElseThrow()
        check(attempt.paymentState != MixedPaymentState.NONE) { "고객 확인 이전 결제입니다" }
        require(payload.userId == order.userId && payload.amount == order.totalPaymentAmount)
        val expected = order.sellerOrders.map { seller ->
            PaymentCompletedSellerPayload(seller.id, seller.sellerId, seller.paymentAmount, seller.commissionRate,
                seller.commissionAmount, seller.settlementAmount, seller.salesType, seller.items.map(::reservedItem)) }
        require(payload.sellerOrders.map { it.copy(items = it.items.sortedBy { line -> line.orderItemId }) }.sortedBy { it.sellerOrderId } ==
            expected.map { it.copy(items = it.items.sortedBy { line -> line.orderItemId }) }.sortedBy { it.sellerOrderId }) { "결제 구성 원본 불일치" }
        val content = mapper.writeValueAsString(payload)
        if (attempt.paymentState == MixedPaymentState.COMPLETED) {
            require(attempt.paymentResultJson == content) { "결제 완료 원본 충돌" }
            return
        }
        check(attempt.paymentState != MixedPaymentState.FAILED) { "실패로 종결된 결제입니다" }
        val items = items(attempt)
        refreshPayment(order, attempt, items)
        attempt.paymentState = MixedPaymentState.COMPLETED
        attempt.paymentResultJson = content
        items.filter { it.state == AttemptItemState.RESERVED }.forEach { it.state = AttemptItemState.ALLOCATE_PENDING }
        order.expiresAt = null
        refresh(order, attempt, items)
    }

    fun paymentFailed(payload: PaymentFailedPayload) {
        val order = lockedOrder(payload.orderId)
        val attempt = attempts.findById(order.id).orElseThrow()
        require(payload.amount == order.totalPaymentAmount && attempt.paymentState != MixedPaymentState.NONE)
        if (attempt.paymentState in setOf(MixedPaymentState.COMPLETED, MixedPaymentState.FAILED)) return
        attempt.paymentState = MixedPaymentState.FAILED
        val items = items(attempt)
        requestCancellation(order, items.filter { it.state == AttemptItemState.RESERVED }, "PAYMENT_FAILED")
        refresh(order, attempt, items)
    }

    fun cancelSelection(id: UUID, userId: UUID, sellerOrderId: UUID? = null, orderItemId: UUID? = null): List<UUID> {
        val order = lockedOrder(id, userId)
        val attempt = attempts.findById(id).orElseThrow()
        val items = items(attempt)
        if (attempt.paymentState == MixedPaymentState.NONE) {
            if (sellerOrderId != null || orderItemId != null) throw OrderCompositionConflictException()
            if (order.status !in TERMINAL) stop(order, attempt, items, OrdersStatus.ORDER_CANCELED)
            refresh(order, attempt, items)
            return emptyList()
        }
        val selected = items.filter { (sellerOrderId == null || it.sellerOrderId == sellerOrderId) &&
            (orderItemId == null || it.orderItemId == orderItemId) && it.state !in setOf(AttemptItemState.REJECTED, AttemptItemState.RELEASED) }
        require(selected.isNotEmpty()) { "취소할 주문 품목이 없습니다" }
        if (orderItemId != null) require(selected.single().salesType == SalesType.DIRECT_PURCHASE) { "입점은 판매자 주문 단위로 취소하세요" }
        requestCancellation(order, selected, "USER_CANCELED")
        refresh(order, attempt, items)
        return order.sellerOrders.filter { seller -> seller.salesType == SalesType.MARKETPLACE && seller.status == SellerOrderStatus.CONFIRMED &&
            selected.any { item -> item.sellerOrderId == seller.id } }.map { it.id }
    }

    fun applyAllocation(id: UUID, itemId: UUID, result: RemoteReservation?) {
        val order = lockedOrder(id)
        val attempt = attempts.findById(id).orElseThrow()
        val items = items(attempt)
        val item = items.single { it.orderItemId == itemId }
        if (item.state != AttemptItemState.ALLOCATE_PENDING) return
        when (result?.state) {
            "ALLOCATED" -> { item.state = AttemptItemState.ALLOCATED; item.warehouseId = result.warehouseId; item.reason = null }
            "EXPIRED", "REJECTED", "INVALIDATED", "RELEASED", "CANCELED" -> {
                val targets = if (item.salesType == SalesType.MARKETPLACE) items.filter { it.sellerOrderId == item.sellerOrderId } else listOf(item)
                requestCancellation(order, targets, "ALLOCATION_FAILED")
            }
            else -> item.reason = "ALLOCATION_UNCONFIRMED"
        }
        refresh(order, attempt, items)
    }

    fun applyCancellation(id: UUID, itemId: UUID, canceled: Boolean, rejected: Boolean = false) {
        val order = lockedOrder(id)
        val attempt = attempts.findById(id).orElseThrow()
        val items = items(attempt)
        val item = items.single { it.orderItemId == itemId }
        if (rejected && item.state == AttemptItemState.CANCEL_PENDING) {
            item.state = AttemptItemState.ALLOCATED
            item.cancellationRejected = true
            item.reason = "WAREHOUSE_CANCELLATION_REJECTED"
        }
        if (canceled && item.state == AttemptItemState.CANCEL_PENDING) {
            item.state = AttemptItemState.CANCELED
            stockAdjustment(order, item, false)
        }
        refresh(order, attempt, items)
    }

    fun returnInspected(payload: DirectFulfillmentPayload) = completeDirectCancellation(payload, "RETURN_INSPECTED")

    fun ineligibleCanceled(payload: DirectFulfillmentPayload) = completeDirectCancellation(payload, "STOCK_INELIGIBLE")

    private fun completeDirectCancellation(payload: DirectFulfillmentPayload, reason: String) {
        val order = lockedOrder(payload.orderId)
        val attempt = attempts.findById(order.id).orElseThrow()
        val items = items(attempt)
        val item = items.single { it.orderItemId == payload.orderItemId }
        require(order.userId == payload.userId && item.salesType == SalesType.DIRECT_PURCHASE &&
            item.sellerOrderId == payload.sellerOrderId && item.reservationId == payload.reservationId &&
            item.productId == payload.skuId && item.warehouseId == payload.warehouseId && item.quantity.toLong() == payload.quantity)
        // 취소 HTTP 복구가 부적격 회수 이벤트보다 먼저 종결할 수 있다.
        if (item.state == AttemptItemState.CANCELED) { require(item.reason == reason || reason == "STOCK_INELIGIBLE"); return }
        check(item.state in setOf(AttemptItemState.ALLOCATED, AttemptItemState.CANCEL_PENDING) && item.confirmationPublished)
        item.state = AttemptItemState.CANCELED
        item.cancellationRejected = false
        item.reason = reason
        refresh(order, attempt, items)
    }

    fun refundReceived(payload: PaymentRefundedPayload) {
        val order = lockedOrder(payload.orderId)
        val attempt = attempts.findById(order.id).orElseThrow()
        val items = items(attempt)
        refreshPayment(order, attempt, items)
        val seller = order.sellerOrders.single { it.id == payload.sellerOrderId }
        require(payload.sellerId == seller.sellerId && payload.salesType == seller.salesType)
        val targets = items.filter { it.sellerOrderId == seller.id && (payload.orderItemId == null || it.orderItemId == payload.orderItemId) }
        require(targets.isNotEmpty() && targets.all { it.state == AttemptItemState.CANCELED && it.refundRequested })
        require((seller.salesType == SalesType.DIRECT_PURCHASE) == (payload.orderItemId != null))
        val amount = if (payload.orderItemId == null) seller.paymentAmount else seller.items.single { it.id == payload.orderItemId }.let {
            Math.addExact(it.totalAmount, it.deliveryFeeAllocation) }
        require(payload.amount == amount)
        val payment = mapper.readValue(attempt.paymentResultJson, PaymentCompletedPayload::class.java)
        require(payment.paymentId == payload.paymentId)
        targets.forEach { require(it.refundReceipt == null || it.refundReceipt == payload); it.refundReceipt = payload }
        refresh(order, attempt, items)
    }

    fun reconcileRefunds(id: UUID) {
        val order = lockedOrder(id)
        val attempt = attempts.findById(id).orElseThrow()
        val items = items(attempt)
        if (attempt.paymentState == MixedPaymentState.COMPLETED) recordRefunds(order, items, true)
    }

    private fun publishPayment(order: Orders, attempt: MixedOrderAttempt, items: List<AttemptItem>) {
        val views = sellers(attempt, items)
        require(order.totalPaymentAmount > 0) { "결제 금액은 양수여야 합니다" }
        for (view in views) {
            val seller = SellerOrder(order, view.sellerId, view.itemTotalAmount, view.deliveryFee, view.paymentAmount,
                view.commissionRate, view.commissionAmount, view.settlementAmount, salesType = view.salesType).also { it.id = view.sellerOrderId }
            view.items.forEach { line -> seller.addItem(OrderItem(seller, line.productId, line.productName,
                line.quantity, line.unitPrice, line.totalAmount, line.reservationId,
                deliveryFeeAllocation = line.deliveryFeeAllocation).also { it.id = line.orderItemId }) }
            order.addSellerOrder(seller)
        }
        attempt.paymentState = MixedPaymentState.READY
        val payload = OrderReservedPayload(order.id, order.userId, order.totalItemAmount, order.totalDeliveryFee,
            order.totalPaymentAmount, order.totalCommissionAmount, order.totalSettlementAmount, order.sellerOrders.map { seller ->
                OrderReservedSellerPayload(seller.id, seller.sellerId, seller.itemTotalAmount, seller.deliveryFee, seller.paymentAmount,
                    seller.commissionRate, seller.commissionAmount, seller.settlementAmount, seller.items.map(::reservedItem), seller.salesType)
            }, order.expiresAt, order.createdAt, attempt.compositionVersion, attempt.confirmedVersion)
        record(order, "ORDER_RESERVED", payload)
    }

    private fun reservedItem(item: OrderItem) = OrderReservedItemPayload(item.id, item.productId, item.productName,
        item.quantity, item.unitPrice, item.totalAmount, item.reservationId, deliveryFeeAllocation = item.deliveryFeeAllocation)

    private fun requestCancellation(order: Orders, selected: List<AttemptItem>, reason: String) {
        selected.filter { !it.cancellationRejected && it.state !in setOf(AttemptItemState.CANCELED, AttemptItemState.CANCEL_PENDING) }.forEach { item ->
            val seller = order.sellerOrders.single { it.id == item.sellerOrderId }
            if (item.salesType == SalesType.DIRECT_PURCHASE || seller.status != SellerOrderStatus.CONFIRMED) {
                item.state = AttemptItemState.CANCEL_PENDING
                item.reason = reason
            }
        }
    }

    private fun refreshPayment(order: Orders, attempt: MixedOrderAttempt, items: List<AttemptItem>) {
        if (attempt.paymentState == MixedPaymentState.READY && clock.instant() >= attempt.expiresAt) {
            requestCancellation(order, items.filter { it.state == AttemptItemState.RESERVED }, "RESERVATION_EXPIRED")
            order.status = OrdersStatus.ORDER_EXPIRED
        }
        for (seller in order.sellerOrders) {
            val group = items.filter { it.sellerOrderId == seller.id }
            // 입점 배송 취소는 기존 executor의 재고/환불 이벤트를 보존한다.
            if (seller.salesType == SalesType.MARKETPLACE && seller.status == SellerOrderStatus.CANCELED && group.any { it.confirmationPublished }) {
                group.forEach { it.state = AttemptItemState.CANCELED; it.refundRequested = true; it.stockAdjustmentRecorded = false }
            }
            for (line in seller.items) line.status = when (group.single { it.orderItemId == line.id }.state) {
                AttemptItemState.ALLOCATED -> OrderItemStatus.CONFIRMED
                AttemptItemState.CANCEL_PENDING -> OrderItemStatus.CANCELLATION_PENDING
                AttemptItemState.CANCELED -> OrderItemStatus.CANCELED
                else -> OrderItemStatus.RESERVED
            }
            seller.status = when {
                seller.items.all { it.status == OrderItemStatus.CANCELED } -> SellerOrderStatus.CANCELED
                seller.items.any { it.status == OrderItemStatus.CANCELED } -> SellerOrderStatus.PARTIALLY_CANCELED
                seller.items.all { it.status == OrderItemStatus.CONFIRMED } -> SellerOrderStatus.CONFIRMED
                else -> SellerOrderStatus.RESERVED
            }
            if (seller.salesType == SalesType.MARKETPLACE && seller.status == SellerOrderStatus.CONFIRMED && group.none { it.confirmationPublished }) {
                record(order, "ORDER_CONFIRMED", OrderConfirmedPayload(order.id, order.userId, seller.paymentAmount,
                    listOf(OrderConfirmedSellerPayload(seller.id, seller.sellerId, seller.paymentAmount, seller.salesType)), clock.instant()))
                group.forEach { it.confirmationPublished = true }
            }
        }
        for (item in items.filter { it.salesType == SalesType.DIRECT_PURCHASE && it.state == AttemptItemState.ALLOCATED && !it.confirmationPublished }) {
            record(order, "DIRECT_FULFILLMENT_REQUESTED", DirectFulfillmentPayload(order.id, order.userId, item.sellerOrderId,
                item.orderItemId, item.reservationId, item.productId, requireNotNull(item.warehouseId), item.quantity.toLong()))
            item.confirmationPublished = true
        }
        recordRefunds(order, items, false)
        if (attempt.paymentState != MixedPaymentState.READY || order.status != OrdersStatus.ORDER_EXPIRED) {
            if (attempt.paymentState == MixedPaymentState.READY && items.any { it.state == AttemptItemState.RESERVED } &&
                items.none { it.state == AttemptItemState.CANCEL_PENDING || it.state == AttemptItemState.CANCELED }) order.status = OrdersStatus.ORDER_AWAITING_PAYMENT
            else order.recomputeStatus()
        }
        attempt.itemsJson = mapper.writeValueAsString(items)
        attempt.nextRecoveryAt = if (items.any { it.state in setOf(AttemptItemState.RESERVED, AttemptItemState.ALLOCATE_PENDING, AttemptItemState.CANCEL_PENDING) ||
                (it.refundRequested && it.refundReceipt == null && attempt.paymentState != MixedPaymentState.FAILED) } ||
                order.sellerOrders.any { it.cancellationStatus == CancellationStatus.PENDING })
            attempt.nextRecoveryAt ?: clock.instant().plusSeconds(5) else null
    }

    private fun recordRefunds(order: Orders, items: List<AttemptItem>, reconcile: Boolean) {
        for (seller in order.sellerOrders) {
            val group = items.filter { it.sellerOrderId == seller.id && it.state == AttemptItemState.CANCELED }
            val targets = if (seller.salesType == SalesType.MARKETPLACE) {
                if (seller.status == SellerOrderStatus.CANCELED && group.isNotEmpty()) listOf(group) else emptyList()
            } else group.map { listOf(it) }
            for (target in targets) {
                if (target.all { it.refundReceipt != null } || (!reconcile && target.all { it.refundRequested })) continue
                val lines = seller.items.filter { line -> target.any { it.orderItemId == line.id } }
                val amount = if (seller.salesType == SalesType.MARKETPLACE) seller.paymentAmount else
                    lines.sumOf { Math.addExact(it.totalAmount, it.deliveryFeeAllocation) }
                if (amount > 0) record(order, if (reconcile) "ORDER_CANCELLATION_RECONCILIATION_REQUESTED" else "ORDER_CANCELED",
                    OrderCanceledPayload(order.id, order.userId, target.first().reason ?: "USER_CANCELED", listOf(
                        OrderCanceledSellerPayload(seller.id, seller.sellerId, amount, lines.map {
                            OrderCanceledItemPayload(it.id, it.productId, it.quantity, it.totalAmount) }, seller.salesType,
                            if (seller.salesType == SalesType.DIRECT_PURCHASE) target.single().orderItemId else null))))
                target.forEach { it.refundRequested = amount > 0 }
            }
        }
    }

    private fun record(order: Orders, type: String, payload: Any) = outbox.record(listOf(OutboxEvent(
        aggregateType = "ORDER", aggregateId = order.id, eventType = type, payload = mapper.writeValueAsString(payload))))

    private fun lockedOrder(id: UUID, userId: UUID? = null): Orders {
        val order = orders.findByIdForUpdate(id) ?: throw OrderNotFoundException(id)
        if (userId != null && order.userId != userId) throw OrderForbiddenException(id)
        return order
    }

    private fun items(attempt: MixedOrderAttempt): List<AttemptItem> =
        mapper.readValue(attempt.itemsJson, object : TypeReference<List<AttemptItem>>() {})

    private fun changed(attempt: MixedOrderAttempt) {
        attempt.compositionVersion = Math.addExact(attempt.compositionVersion, 1)
        attempt.confirmedVersion = null
    }

    private fun stop(order: Orders, attempt: MixedOrderAttempt, items: List<AttemptItem>, status: OrdersStatus) {
        order.status = status
        changed(attempt)
        items.filter { it.state in setOf(AttemptItemState.PENDING, AttemptItemState.RESERVED) }.forEach {
            it.state = AttemptItemState.RELEASE_PENDING
            it.reason = status.name
        }
    }

    private fun refresh(order: Orders, attempt: MixedOrderAttempt, items: List<AttemptItem>) {
        if (attempt.paymentState != MixedPaymentState.NONE) {
            refreshPayment(order, attempt, items)
            return
        }
        val now = clock.instant()
        if (order.status !in TERMINAL && now >= attempt.expiresAt) stop(order, attempt, items, OrdersStatus.ORDER_EXPIRED)
        val view = sellers(attempt, items)
        order.totalItemAmount = view.sumOf { it.itemTotalAmount }
        order.totalDeliveryFee = view.sumOf { it.deliveryFee }
        order.totalPaymentAmount = Math.addExact(order.totalItemAmount, order.totalDeliveryFee)
        order.totalCommissionAmount = view.sumOf { it.commissionAmount }
        order.totalSettlementAmount = view.sumOf { it.settlementAmount }
        if (order.status !in TERMINAL) order.status = when {
            items.any { it.state == AttemptItemState.PENDING } -> OrdersStatus.ORDER_PROCESSING
            view.isEmpty() -> OrdersStatus.ORDER_FAILED
            attempt.confirmedVersion == attempt.compositionVersion -> OrdersStatus.ORDER_AWAITING_PAYMENT
            else -> OrdersStatus.ORDER_AWAITING_CONFIRMATION
        }
        attempt.itemsJson = mapper.writeValueAsString(items)
        attempt.nextRecoveryAt = if (items.any { it.state in setOf(AttemptItemState.PENDING, AttemptItemState.RESERVED, AttemptItemState.RELEASE_PENDING) })
            attempt.nextRecoveryAt ?: now.plusSeconds(5) else null
    }

    private fun sellers(attempt: MixedOrderAttempt, items: List<AttemptItem>): List<SellerOrderResponse> {
        val reserved = items.filter { it.state == AttemptItemState.RESERVED }
        val direct = reserved.filter { it.salesType == SalesType.DIRECT_PURCHASE }
        val fee = if (direct.isNotEmpty() && direct.sumOf { it.totalAmount } < attempt.freeDeliveryThreshold) attempt.deliveryFee else 0L
        val feeItemId = direct.minByOrNull { it.orderItemId }?.orderItemId
        return reserved.groupBy { it.sellerId }.map { (sellerId, group) ->
            val first = group.first()
            val amount = group.sumOf { it.totalAmount }
            val groupFee = if (first.salesType == SalesType.DIRECT_PURCHASE) fee else 0L
            val rate = if (first.salesType == SalesType.MARKETPLACE) attempt.commissionRate else 0.0
            val commission = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rate)).toLong()
            SellerOrderResponse(first.sellerOrderId, sellerId, amount, groupFee, Math.addExact(amount, groupFee), rate,
                commission, if (first.salesType == SalesType.MARKETPLACE) amount - commission else 0L,
                SellerOrderStatus.RESERVED, CancellationStatus.NONE, null,
                group.map { item -> OrderItemResponse(item.orderItemId, item.productId, item.productName,
                    item.quantity, item.unitPrice, item.totalAmount, item.reservationId, OrderItemStatus.RESERVED,
                    deliveryFeeAllocation = if (item.orderItemId == feeItemId) fee else 0L, warehouseId = item.warehouseId) },
                first.salesType)
        }
    }

    private fun response(order: Orders, attempt: MixedOrderAttempt, items: List<AttemptItem>) = OrderResponse(
        order.id, order.totalItemAmount, order.totalDeliveryFee, order.totalPaymentAmount,
        order.totalCommissionAmount, order.totalSettlementAmount, order.status, order.expiresAt, if (attempt.paymentState == MixedPaymentState.NONE) sellers(attempt, items) else order.sellerOrders.map { seller ->
            SellerOrderResponse.from(seller).let { view -> view.copy(items = view.items.map { line ->
                val item = items.single { it.orderItemId == line.orderItemId }
                line.copy(warehouseId = item.warehouseId, cancellationStatus = when {
                    item.cancellationRejected -> CancellationStatus.REJECTED
                    item.state == AttemptItemState.CANCEL_PENDING -> CancellationStatus.PENDING
                    item.state == AttemptItemState.CANCELED -> CancellationStatus.APPROVED
                    else -> CancellationStatus.NONE
                }) }) } },
        items.filter { it.state in setOf(AttemptItemState.REJECTED, AttemptItemState.RELEASED) }.map {
            FailedOrderItemResponse(it.productId, it.reason ?: "RESERVATION_REJECTED", it.quantity, 0) },
        order.createdAt, order.updatedAt, attempt.compositionVersion,
        order.status == OrdersStatus.ORDER_AWAITING_CONFIRMATION, attempt.confirmedVersion,
        items.filter { it.state in setOf(AttemptItemState.PENDING, AttemptItemState.RELEASE_PENDING, AttemptItemState.ALLOCATE_PENDING, AttemptItemState.CANCEL_PENDING) }.map {
            FailedOrderItemResponse(it.productId, if (it.state == AttemptItemState.RELEASE_PENDING) "RELEASE_PENDING" else "RESULT_UNCONFIRMED", it.quantity, 0) },
        paymentReady = attempt.paymentState == MixedPaymentState.READY && order.status == OrdersStatus.ORDER_AWAITING_PAYMENT &&
            items.none { it.state == AttemptItemState.CANCEL_PENDING || it.state == AttemptItemState.CANCELED } && clock.instant() < attempt.expiresAt)

    private fun stockAdjustment(order: Orders, item: AttemptItem, reserve: Boolean) {
        if (item.salesType != SalesType.MARKETPLACE || item.stockAdjustmentRecorded == reserve) return
        outbox.record(listOf(OutboxEvent(aggregateType = "INVENTORY", aggregateId = item.productId,
            eventType = "INVENTORY_STOCK_ADJUSTED", payload = mapper.writeValueAsString(InventoryStockAdjustedPayload(
                order.id, item.productId, item.reservationId, if (reserve) -item.quantity else item.quantity,
                if (reserve) "RESERVED" else "RELEASED")), status = OutboxStatus.PENDING)))
        item.stockAdjustmentRecorded = reserve
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        val TERMINAL = setOf(OrdersStatus.ORDER_CANCELED, OrdersStatus.ORDER_EXPIRED, OrdersStatus.ORDER_FAILED)
    }
}

data class AttemptSnapshot(val orderId: UUID, val expiresAt: Instant, val items: List<AttemptItem>, val response: OrderResponse, val recoveryCursor: Int = 0)
