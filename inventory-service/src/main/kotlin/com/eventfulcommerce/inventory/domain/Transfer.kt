package com.eventfulcommerce.inventory.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class TransferStatus { HELD, IN_TRANSIT, PARTIALLY_RECEIVED, RECEIVED, CANCELED }

@Entity
@Table(name = "inventory_transfer")
class TransferOrder(
    @Id val id: UUID,
    @Column(nullable = false) val sourceWarehouseId: UUID,
    @Column(nullable = false) val destinationWarehouseId: UUID,
    @Column(nullable = false) val expectedArrivalAt: Instant,
    @Column(nullable = false) val actorId: UUID,
    @Column(nullable = false, length = 500) val reason: String,
    @get:JsonIgnore @Column(nullable = false, columnDefinition = "text") val requestContent: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: TransferStatus = TransferStatus.HELD,
    @get:JsonIgnore @Column(columnDefinition = "text") var dispatchContent: String? = null,
    @get:JsonIgnore @Column(columnDefinition = "text") var cancellationContent: String? = null,
    @Column(nullable = false) val createdAt: Instant,
    @Column var dispatchedAt: Instant? = null,
    @Column var dispatchedBy: UUID? = null,
    @Column var canceledAt: Instant? = null,
    @Column var canceledBy: UUID? = null
)

@Entity
@Table(name = "inventory_transfer_line")
class TransferLine(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val transferId: UUID,
    @Column(nullable = false) val sourceWarehouseId: UUID,
    @Column(nullable = false) val skuId: UUID,
    @Column(nullable = false) val lotId: UUID,
    @Column(nullable = false) val quantity: Long,
    @Column(nullable = false) var receivedQuantity: Long = 0
)

@Entity
@Table(name = "inventory_transfer_receipt")
class TransferReceipt(
    @Id val id: UUID,
    @Column(nullable = false) val transferId: UUID,
    @Column(nullable = false) val actorId: UUID,
    @Column(nullable = false, length = 500) val reason: String,
    @get:JsonIgnore @Column(nullable = false, columnDefinition = "text") val requestContent: String,
    @Column(nullable = false) val receivedAt: Instant
)

@Entity
@Table(name = "inventory_transfer_receipt_line")
class TransferReceiptLine(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val receiptId: UUID,
    @Column(nullable = false) val transferId: UUID,
    @Column(nullable = false) val lotId: UUID,
    @Column(nullable = false) val quantity: Long
)
