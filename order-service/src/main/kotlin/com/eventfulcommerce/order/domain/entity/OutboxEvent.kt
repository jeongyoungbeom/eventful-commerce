package com.eventfulcommerce.order.domain.entity

import com.eventfulcommerce.order.domain.OutboxStatus
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "outbox_event")
class OutboxEvent(
    @Column(nullable = false)
    val aggregateType: String,

    @Column(nullable = false)
    val aggregateId: UUID,

    @Column(nullable = false)
    val eventType: String,

    @Column(nullable = false, columnDefinition = "jsonb")
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: OutboxStatus = OutboxStatus.PENDING,

    @Column(nullable = false)
    var retryCount: Int = 0,
    var lastError: String? = null,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
    var sentAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID
}