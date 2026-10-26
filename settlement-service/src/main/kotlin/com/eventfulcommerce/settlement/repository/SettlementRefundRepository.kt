package com.eventfulcommerce.settlement.repository

import com.eventfulcommerce.settlement.domain.entity.SettlementRefund
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SettlementRefundRepository : JpaRepository<SettlementRefund, UUID>
