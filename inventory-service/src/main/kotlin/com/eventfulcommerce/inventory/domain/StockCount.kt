package com.eventfulcommerce.inventory.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class StockCountStatus { OPEN, MEASURED, APPLIED, CANCELED }

@Entity
@Table(name = "inventory_stock_count")
class StockCount(
    @Id val id: UUID,
    @Column(nullable = false) val skuId: UUID,
    @Column(nullable = false) val warehouseId: UUID,
    @Column(nullable = false) val lotId: UUID,
    @Column(nullable = false) val baseOnHand: Long,
    @Column(nullable = false) val baseBlocked: Long,
    @Column(nullable = false) val baseVersion: Long,
    @Column(nullable = false) val startedAt: Instant,
    @Column(nullable = false) val startedBy: UUID,
    @Column(nullable = false, length = 500) val reason: String,
    @Column val reconciliationId: UUID? = null,
    @get:JsonIgnore @Column(nullable = false, columnDefinition = "text") val requestContent: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: StockCountStatus = StockCountStatus.OPEN,
    @Column var countedQuantity: Long? = null,
    @Column var measuredAt: Instant? = null,
    @Column var measuredBy: UUID? = null,
    @get:JsonIgnore @Column(columnDefinition = "text") var measurementContent: String? = null,
    @Column(length = 500) var measurementReason: String? = null,
    @Column var adjustment: Long? = null,
    @Column var appliedAt: Instant? = null,
    @Column var appliedBy: UUID? = null,
    @get:JsonIgnore @Column(columnDefinition = "text") var approvalContent: String? = null,
    @Column(length = 500) var approvalReason: String? = null,
    @Column var canceledAt: Instant? = null,
    @Column var canceledBy: UUID? = null,
    @get:JsonIgnore @Column(columnDefinition = "text") var cancellationContent: String? = null,
    @Column(length = 500) var cancellationReason: String? = null
)

@Entity
@Table(name = "inventory_reconciliation")
class StockReconciliation(
    @Id val id: UUID,
    @Column(nullable = false) val skuId: UUID,
    @Column val warehouseId: UUID?,
    @Column val previousReportId: UUID?,
    @Column(nullable = false) val checkedAt: Instant,
    @Column(nullable = false) val actorId: UUID,
    @Column(nullable = false, length = 500) val reason: String,
    @Column(nullable = false) val pageNumber: Int,
    @Column(nullable = false) val pageSize: Int,
    @Column(nullable = false) val hasNext: Boolean,
    @Column(nullable = false) val mismatchCount: Int,
    @get:JsonIgnore @Column(nullable = false, columnDefinition = "text") val requestContent: String,
    @get:JsonIgnore @Column(nullable = false, columnDefinition = "text") val resultContent: String
)
