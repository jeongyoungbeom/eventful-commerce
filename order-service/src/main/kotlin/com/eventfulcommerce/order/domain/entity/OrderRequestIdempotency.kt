package com.eventfulcommerce.order.domain.entity

import com.eventfulcommerce.common.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * One row represents the final response for one user-provided Idempotency-Key.
 * It is inserted and completed in the same transaction as the order and outbox event.
 */
@Entity
@Table(
    name = "order_request_idempotency",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_order_request_idempotency_user_key",
            columnNames = ["user_id", "idempotency_key"]
        )
    ],
    indexes = [Index(name = "idx_order_request_idempotency_expires_at", columnList = "expires_at")]
)
class OrderRequestIdempotency(
    @Id
    val id: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,

    @Column(name = "request_hash", nullable = false, length = 64)
    val requestHash: String,

    @Column(name = "response_json", columnDefinition = "text")
    var responseJson: String? = null,

    @Column(name = "order_id")
    var orderId: UUID? = null,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant
) : BaseTimeEntity()
