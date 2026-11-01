package com.eventfulcommerce.inventory.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class OutboundStatus { READY, PICKING, PACKED, SHIPPED, CANCELED }
enum class ReturnStatus { REQUESTED, RECEIVED, INSPECTED }

// shortcut: 초기 지시는 한 주문 품목 전량이다. 같은 창고의 합배송이 필요하면 묶음 문서를 추가한다.
@Entity
@Table(name = "inventory_outbound")
class OutboundOrder(
    @Id val id: UUID,
    @Column(nullable = false) val orderId: UUID,
    @Column(nullable = false) val sellerOrderId: UUID,
    @Column(nullable = false) val orderItemId: UUID,
    @Column(nullable = false) val warehouseId: UUID,
    @get:JsonIgnore @Column(nullable = false, columnDefinition = "text") val requestContent: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: OutboundStatus,
    @Column(nullable = false) var cancellationRequested: Boolean = false,
    @Column var cancellationApproved: Boolean? = null,
    @get:JsonIgnore @Column(columnDefinition = "text") var decisionContent: String? = null,
    @get:JsonIgnore @Column(columnDefinition = "text") var ineligibilityCancellationContent: String? = null,
    @get:JsonIgnore @Column(columnDefinition = "text") var pickContent: String? = null,
    @get:JsonIgnore @Column(columnDefinition = "text") var packContent: String? = null,
    @get:JsonIgnore @Column(columnDefinition = "text") var shipContent: String? = null,
    @Column val createdAt: Instant,
    @Column var shippedAt: Instant? = null,
    @Column var deliveredAt: Instant? = null
)

@Entity
@Table(name = "inventory_return_receipt")
class ReturnReceipt(
    @Id val id: UUID,
    @Column(nullable = false, unique = true) val outboundId: UUID,
    @Column(nullable = false, length = 500) val reason: String,
    @Column(nullable = false) val actorId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var status: ReturnStatus = ReturnStatus.REQUESTED,
    @get:JsonIgnore @Column(columnDefinition = "text") var inspectionContent: String? = null,
    @Column val requestedAt: Instant,
    @Column var receivedAt: Instant? = null,
    @Column var inspectedAt: Instant? = null
)
