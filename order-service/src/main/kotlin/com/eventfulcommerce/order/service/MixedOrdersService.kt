package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.SalesType
import com.eventfulcommerce.order.domain.entity.*
import com.eventfulcommerce.order.dto.OrderResponse
import com.eventfulcommerce.order.exception.OrderCompositionConflictException
import com.eventfulcommerce.order.request.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
@Transactional(propagation = Propagation.NEVER)
class MixedOrdersService(
    private val state: MixedOrderStateService,
    private val legacy: OrdersService,
    private val remote: InventoryServiceClient,
    private val redis: InventoryReservationService
) {
    fun orders(request: OrdersRequest, userId: UUID, key: String): OrderResponse {
        val id = state.prepare(request, userId, key) ?: return legacy.orders(request, userId, key)
        return recover(id, userId)
    }

    fun getOrder(id: UUID, userId: UUID): OrderResponse =
        if (state.contains(id)) recover(id, userId) else legacy.getOrder(id, userId)

    fun decorate(response: OrderResponse, userId: UUID): OrderResponse = response.orderId
        ?.takeIf(state::contains)?.let { state.snapshot(it, userId).response } ?: response

    fun cancel(id: UUID, userId: UUID): OrderCancellationOutcome {
        if (!state.contains(id)) return legacy.cancelOrder(id, userId)
        val outcomes = state.cancelSelection(id, userId).map { legacy.cancelSellerOrder(id, it, userId) }
        val response = recover(id, userId)
        return if (OrderCancellationOutcome.LOCK_UNAVAILABLE in outcomes) OrderCancellationOutcome.LOCK_UNAVAILABLE
            else cancellationOutcome(response)
    }

    fun cancelSeller(id: UUID, sellerId: UUID, userId: UUID): OrderCancellationOutcome {
        if (state.contains(id)) {
            val outcomes = state.cancelSelection(id, userId, sellerOrderId = sellerId).map { legacy.cancelSellerOrder(id, it, userId) }
            val response = recover(id, userId)
            return if (OrderCancellationOutcome.LOCK_UNAVAILABLE in outcomes) OrderCancellationOutcome.LOCK_UNAVAILABLE
                else cancellationOutcome(response, sellerId)
        }
        return legacy.cancelSellerOrder(id, sellerId, userId)
    }

    fun confirm(id: UUID, userId: UUID, request: OrderConfirmationRequest): OrderResponse {
        if (!state.contains(id)) throw OrderCompositionConflictException()
        recover(id, userId)
        return state.confirm(id, userId, request)
    }

    fun contains(id: UUID) = state.contains(id)

    fun cancelItem(id: UUID, itemId: UUID, userId: UUID): OrderCancellationOutcome {
        if (!state.contains(id)) throw OrderCompositionConflictException()
        state.cancelSelection(id, userId, orderItemId = itemId)
        return cancellationOutcome(recover(id, userId), itemId = itemId)
    }

    fun paymentCompleted(payload: com.eventfulcommerce.common.PaymentCompletedPayload) {
        state.paymentCompleted(payload)
        recover(payload.orderId)
    }

    fun paymentFailed(payload: com.eventfulcommerce.common.PaymentFailedPayload) {
        state.paymentFailed(payload)
        recover(payload.orderId)
    }

    fun refundReceived(payload: com.eventfulcommerce.common.PaymentRefundedPayload) = state.refundReceived(payload)

    private fun cancellationOutcome(response: OrderResponse, sellerId: UUID? = null, itemId: UUID? = null): OrderCancellationOutcome {
        val sellers = response.sellerOrders.filter { (sellerId == null || it.sellerOrderId == sellerId) &&
            (itemId == null || it.items.any { line -> line.orderItemId == itemId }) }
        return when {
            (sellers.isEmpty() && response.pendingItems.isNotEmpty()) || response.pendingItems.any { pending -> sellers.flatMap { it.items }.any { it.productId == pending.productId && (itemId == null || it.orderItemId == itemId) } } ||
                sellers.any { it.cancellationStatus == CancellationStatus.PENDING } -> OrderCancellationOutcome.PENDING
            sellers.any { it.cancellationStatus == CancellationStatus.REJECTED || it.items.any { line ->
                (itemId == null || line.orderItemId == itemId) && line.cancellationStatus == CancellationStatus.REJECTED } } -> OrderCancellationOutcome.REJECTED
            else -> OrderCancellationOutcome.CANCELED
        }
    }

    fun recover(id: UUID, userId: UUID? = null): OrderResponse {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        val snapshot = state.snapshot(id, userId)
        val releaseAttempted = mutableSetOf<UUID>()
        val cancelAttempted = mutableSetOf<UUID>()
        state.reconcileRefunds(id)
        val start = snapshot.recoveryCursor
        (snapshot.items.drop(start) + snapshot.items.take(start)).forEach { item ->
            if (System.nanoTime() >= deadline) return@forEach
            when (item.state) {
                AttemptItemState.PENDING, AttemptItemState.RESERVED -> {
                    if (snapshot.response.confirmedVersion != null) return@forEach
                    state.advanceRecovery(id, item.orderItemId)
                    val result = if (item.salesType == SalesType.DIRECT_PURCHASE)
                        remote.resolve(id, item, snapshot.expiresAt) else marketplace(id, item, snapshot.expiresAt)
                    state.applyResult(id, item.orderItemId, result)
                }
                AttemptItemState.ALLOCATE_PENDING -> {
                    state.advanceRecovery(id, item.orderItemId)
                    val result = if (item.salesType == SalesType.DIRECT_PURCHASE) remote.allocate(id, item, snapshot.expiresAt)
                        else allocateMarketplace(item)
                    state.applyAllocation(id, item.orderItemId, result)
                }
                AttemptItemState.CANCEL_PENDING -> {
                    state.advanceRecovery(id, item.orderItemId)
                    cancelAttempted.add(item.orderItemId)
                    cancelReservation(id, item, snapshot.expiresAt)
                }
                AttemptItemState.RELEASE_PENDING -> {
                    state.advanceRecovery(id, item.orderItemId)
                    releaseAttempted.add(item.orderItemId)
                    release(id, item, snapshot.expiresAt)
                }
                else -> Unit
            }
        }
        // 취소가 원격 호출 중 도착했다면 늦은 성공을 응답하기 전에 해제를 다시 시도한다.
        state.snapshot(id, userId).items.filter { it.state == AttemptItemState.RELEASE_PENDING && it.orderItemId !in releaseAttempted }.forEach {
            if (System.nanoTime() < deadline) release(id, it, snapshot.expiresAt)
        }
        state.snapshot(id, userId).items.filter { it.state == AttemptItemState.CANCEL_PENDING && it.orderItemId !in cancelAttempted }.forEach {
            if (System.nanoTime() < deadline) cancelReservation(id, it, snapshot.expiresAt)
        }
        return state.completePass(id, userId)
    }

    private fun allocateMarketplace(item: AttemptItem): RemoteReservation? = try {
        val result = redis.commit(item.productId.toString(), item.reservationId, item.quantity, requireUnexpired = true)
        if (result.successful) RemoteReservation("ALLOCATED")
        else if (result == InventoryReservationActionResult.TERMINAL_CONFLICT) RemoteReservation("EXPIRED") else null
    } catch (_: Exception) { null }

    private fun cancelReservation(id: UUID, item: AttemptItem, expiresAt: Instant) {
        val remoteResult = if (item.salesType == SalesType.DIRECT_PURCHASE) remote.cancel(id, item, expiresAt) else null
        val canceled = if (item.salesType == SalesType.DIRECT_PURCHASE)
            remoteResult?.state in setOf("CANCELED", "RELEASED", "EXPIRED", "REJECTED", "INVALIDATED") else try {
            val result = redis.releaseIntent(item.productId.toString(), id, item.reservationId, item.quantity, expiresAt)
            result.successful || (result == InventoryReservationActionResult.TERMINAL_CONFLICT &&
                redis.restock(item.productId.toString(), item.reservationId, item.quantity).successful)
        } catch (_: Exception) { false }
        state.applyCancellation(id, item.orderItemId, canceled, rejected = remoteResult?.reason == "REJECTED" ||
            remoteResult?.state in setOf("SHIPPED", "RETURNED"))
    }

    private fun marketplace(id: UUID, item: AttemptItem, expiresAt: Instant): RemoteReservation? = try {
        var metadata = redis.getReservationMetadata(item.productId.toString(), item.reservationId)
        if (metadata == null) {
            val reserved = redis.reserve(item.productId.toString(), id, item.quantity, 600, item.reservationId,
                expiresAt, retainFailure = true)
            if (reserved == null) RemoteReservation("REJECTED", "INSUFFICIENT_STOCK") else {
                metadata = redis.getReservationMetadata(item.productId.toString(), item.reservationId)
                metadata?.let { marketplaceState(id, item, expiresAt, it) }
            }
        } else marketplaceState(id, item, expiresAt, metadata)
    } catch (_: Exception) { null }

    private fun marketplaceState(id: UUID, item: AttemptItem, expiresAt: Instant, metadata: InventoryReservationMetadata): RemoteReservation {
        check(metadata.orderId == id && metadata.productId == item.productId.toString() &&
            metadata.quantity == item.quantity && metadata.expiresAt.toEpochMilli() == expiresAt.toEpochMilli())
        return when (metadata.state) {
            InventoryReservationState.RESERVED -> if (Instant.now() < expiresAt) RemoteReservation("RESERVED")
                else if (redis.releaseIntent(item.productId.toString(), id, item.reservationId, item.quantity, expiresAt).successful)
                    RemoteReservation("EXPIRED") else RemoteReservation("PENDING")
            InventoryReservationState.RELEASED, InventoryReservationState.RESTOCKED -> RemoteReservation("RELEASED")
            InventoryReservationState.COMMITTED -> RemoteReservation("ALLOCATED")
        }
    }

    private fun release(id: UUID, item: AttemptItem, expiresAt: Instant) {
        val released = if (item.salesType == SalesType.DIRECT_PURCHASE) remote.release(id, item, expiresAt) != null
            else try { redis.releaseIntent(item.productId.toString(), id, item.reservationId, item.quantity, expiresAt).successful }
                catch (_: Exception) { false }
        state.applyRelease(id, item.orderItemId, released)
    }
}
