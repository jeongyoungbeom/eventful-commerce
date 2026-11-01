package com.eventfulcommerce.product.domain.entity

import com.eventfulcommerce.common.SalesType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "official_seller")
class OfficialSeller(
    @Id val id: UUID = SalesType.OFFICIAL_SELLER_ID,
    @Column(nullable = false, length = 100) val displayName: String
)
