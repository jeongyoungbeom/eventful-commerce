package com.eventfulcommerce.settlement.service

import com.eventfulcommerce.settlement.config.SettlementConfig
import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.domain.entity.Settlement
import com.eventfulcommerce.settlement.dto.SettlementResponse
import com.eventfulcommerce.settlement.dto.SettlementSummaryResponse
import com.eventfulcommerce.settlement.exception.SettlementNotFoundException
import com.eventfulcommerce.settlement.repository.SettlementRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class SettlementService(
    private val settlementRepository: SettlementRepository,
    private val settlementConfig: SettlementConfig
) {
    @Transactional
    fun createSettlement(
        paymentId: UUID,
        orderId: UUID,
        sellerId: UUID,
        userId: UUID,
        totalAmount: Long
    ): Settlement {
        settlementRepository.findByPaymentId(paymentId)?.let {
            logger.info { "이미 처리된 결제 정산: paymentId=$paymentId" }
            return it
        }

        val settlement = Settlement(
            paymentId = paymentId,
            orderId = orderId,
            sellerId = sellerId,
            userId = userId,
            totalAmount = totalAmount,
            platformFee = settlementConfig.calculatePlatformFee(totalAmount),
            sellerAmount = settlementConfig.calculateSellerAmount(totalAmount)
        )

        val saved = settlementRepository.save(settlement)
        logger.info { "정산 생성: settlementId=${saved.id}, sellerId=$sellerId, sellerAmount=${saved.sellerAmount}" }
        return saved
    }

    @Transactional
    fun pay(settlementId: UUID): SettlementResponse {
        val settlement = settlementRepository.findById(settlementId)
            .orElseThrow { SettlementNotFoundException(settlementId) }
        settlement.pay()
        logger.info { "정산 지급 완료: settlementId=$settlementId, sellerId=${settlement.sellerId}" }
        return SettlementResponse.from(settlement)
    }

    @Transactional(readOnly = true)
    fun getMySettlements(sellerId: UUID, status: SettlementStatus?): List<SettlementResponse> {
        val settlements = if (status != null) {
            settlementRepository.findBySellerIdAndStatusOrderByCreatedAtDesc(sellerId, status)
        } else {
            settlementRepository.findBySellerIdOrderByCreatedAtDesc(sellerId)
        }
        return settlements.map { SettlementResponse.from(it) }
    }

    @Transactional(readOnly = true)
    fun getMySummary(sellerId: UUID): SettlementSummaryResponse {
        // DB 집계 쿼리로 메모리에 올리지 않고 상태별 합계를 직접 조회
        val rows = settlementRepository.findSummaryBySellerId(sellerId)
            .associateBy { it.status }

        val pending = rows[SettlementStatus.PENDING]
        val confirmed = rows[SettlementStatus.CONFIRMED]
        val paid = rows[SettlementStatus.PAID]

        return SettlementSummaryResponse(
            sellerId = sellerId,
            pendingCount = pending?.count?.toInt() ?: 0,
            pendingAmount = pending?.totalAmount ?: 0L,
            confirmedCount = confirmed?.count?.toInt() ?: 0,
            confirmedAmount = confirmed?.totalAmount ?: 0L,
            paidCount = paid?.count?.toInt() ?: 0,
            paidAmount = paid?.totalAmount ?: 0L,
            totalSellerAmount = rows.values.sumOf { it.totalAmount }
        )
    }

    @Transactional(readOnly = true)
    fun getAllSettlements(status: SettlementStatus?): List<SettlementResponse> {
        val settlements = if (status != null) {
            settlementRepository.findByStatus(status)
        } else {
            settlementRepository.findAll()
        }
        return settlements.map { SettlementResponse.from(it) }
    }
}
