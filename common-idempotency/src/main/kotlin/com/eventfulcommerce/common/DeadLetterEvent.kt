package com.eventfulcommerce.common

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "dead_letter_event",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_dead_letter_event_origin",
            columnNames = ["original_topic", "original_partition", "original_offset"]
        )
    ]
)
class DeadLetterEvent(
    @Column(name = "original_topic", nullable = false)
    val originalTopic: String,

    @Column(name = "original_partition", nullable = false)
    val originalPartition: Int,

    @Column(name = "original_offset", nullable = false)
    val originalOffset: Long,

    @Column(name = "record_key")
    val recordKey: String? = null,

    @Column(nullable = false, columnDefinition = "text")
    val payload: String,

    @Column(name = "exception_class")
    val exceptionClass: String? = null,

    @Column(name = "exception_message", columnDefinition = "text")
    val exceptionMessage: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: DeadLetterEventStatus = DeadLetterEventStatus.PENDING,

    @Column(name = "replay_count", nullable = false)
    var replayCount: Int = 0,

    @Column(name = "last_replayed_at")
    var lastReplayedAt: Instant? = null,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    lateinit var id: UUID

    @Version
    var version: Long = 0
}
