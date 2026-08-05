package com.eventfulcommerce.common

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "outbox_event",
    indexes = [
        Index(name = "idx_outbox_event_publishable", columnList = "status,next_attempt_at,created_at"),
        Index(name = "idx_outbox_event_processing_lease", columnList = "status,processing_started_at")
    ]
)
class OutboxEvent(
    @Column(nullable = false)
    val aggregateType: String,

    @Column(nullable = false)
    val aggregateId: UUID,

    @Column(nullable = false)
    val eventType: String,

    @Column(nullable = false, columnDefinition = "text")
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OutboxStatus = OutboxStatus.PENDING,

    @Column(nullable = false)
    var retryCount: Int = 0,

    @Column(name = "last_error", columnDefinition = "text")
    var lastError: String? = null,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now(),

    @Column(name = "processing_token")
    var processingToken: UUID? = null,

    @Column(name = "processing_started_at")
    var processingStartedAt: Instant? = null,

    @Column(name = "failed_at")
    var failedAt: Instant? = null,

    @Column(name = "requeue_count", nullable = false)
    var requeueCount: Int = 0,

    @Column(name = "last_requeued_at")
    var lastRequeuedAt: Instant? = null,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    var sentAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}
