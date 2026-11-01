package com.eventfulcommerce.order.domain.entity

import com.eventfulcommerce.common.SalesType
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class AttemptItemState { PENDING, RESERVED, REJECTED, RELEASE_PENDING, RELEASED, ALLOCATE_PENDING, ALLOCATED, CANCEL_PENDING, CANCELED }
enum class MixedPaymentState { NONE, READY, COMPLETED, FAILED }

data class AttemptItem(
    val orderItemId: UUID,
    val reservationId: UUID,
    val sellerOrderId: UUID,
    val productId: UUID,
    val productName: String,
    val sellerId: UUID,
    val salesType: SalesType,
    val quantity: Int,
    val unitPrice: Long,
    var state: AttemptItemState,
    var reason: String? = null,
    var warehouseId: UUID? = null,
    var stockAdjustmentRecorded: Boolean = false,
    var confirmationPublished: Boolean = false,
    var refundRequested: Boolean = false,
    var cancellationRejected: Boolean = false,
    var refundReceipt: com.eventfulcommerce.common.PaymentRefundedPayload? = null
) {
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val totalAmount: Long get() = Math.multiplyExact(unitPrice, quantity.toLong())
}

@Entity
@Table(name = "mixed_order_attempt", indexes = [Index(name = "idx_mixed_attempt_recovery", columnList = "next_recovery_at")])
class MixedOrderAttempt(
    @Id val id: UUID,
    @Column(nullable = false, columnDefinition = "text") var itemsJson: String,
    @Column(nullable = false) val expiresAt: Instant,
    @Column(nullable = false) val deliveryFee: Long,
    @Column(nullable = false) val freeDeliveryThreshold: Long,
    @Column(nullable = false) val commissionRate: Double,
    @Column(nullable = false) var compositionVersion: Long = 1,
    @Column var confirmedVersion: Long? = null,
    @Column var nextRecoveryAt: Instant? = Instant.now(),
    @Enumerated(EnumType.STRING) @Column(nullable = false) var paymentState: MixedPaymentState = MixedPaymentState.NONE,
    @Column(columnDefinition = "text") var paymentResultJson: String? = null,
    @Column(nullable = false) var recoveryCursor: Int = 0
)
