package com.eventfulcommerce.payment.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.*

@Entity
@Table(name = "processed_event")
class ProcessedEvent(
    @Id
    @Column(name = "event_id")
    val eventId: UUID,

    @Column(name = "processed_at", nullable = false)
    val processedAt: Instant = Instant.now(),
)