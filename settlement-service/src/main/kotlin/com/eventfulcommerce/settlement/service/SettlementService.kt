package com.eventfulcommerce.settlement.service

import com.eventfulcommerce.settlement.config.SettlementConfig
import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.domain.entity.Settlement
import com.eventfulcommerce.settlement.dto.SettlementResponse
import com.eventfulcommerce.settlement.dto.SettlementSummaryResponse
import com.eventfulcommerce.settlement.exception.SettlementNotFoundException
import com.eventfulcommerce.settlement.repository.SettlementRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

@Service
class SettlementService(
    private val settlementRepository: SettlementRepository,
    private val settlementConfig: SettlementConfig,
    private val settlementPayExecutor: SettlementPayExecutor,
    private val redissonClient: RedissonClient
) {
    @Transactional
    fun createSettlement(
        paymentId: UUID,
        orderId: UUID,
        sellerOrderId: UUID,
        sellerId: UUID,
        userId: UUID,
        totalAmount: Long,
        platformFee: Long,
        sellerAmount: Long
    ): Settlement {
        settlementRepository.findBySellerOrderId(sellerOrderId)?.let {
            logger.info { "이미 처리된 판매자 주문 정산: sellerOrderId=$sellerOrderId" }
            return it
        }

        val settlement = Settlement(
            paymentId = paymentId,
            orderId = orderId,
            sellerOrderId = sellerOrderId,
            sellerId = sellerId,
            userId = userId,
            totalAmount = totalAmount,
            platformFee = platformFee,
            sellerAmount = sellerAmount
        )

        val saved = settlementRepository.save(settlement)
        logger.info { "정산 생성: settlementId=${saved.id}, sellerId=$sellerId, sellerAmount=${saved.sellerAmount}" }
        return saved
    }

    @Transactional
    fun applyRefund(sellerOrderId: UUID, refundAmount: Long) {
        val settlement = settlementRepository.findBySellerOrderId(sellerOrderId) ?: run {
            logger.warn { "환불 대상 정산 없음: sellerOrderId=$sellerOrderId" }
            return
        }

        val refundPlatformFee = settlementConfig.calculatePlatformFee(refundAmount)
        val refundSellerAmount = settlementConfig.calculateSellerAmount(refundAmount)
        settlement.applyRefund(refundAmount, refundPlatformFee, refundSellerAmount)
        logger.info { "정산 환불 차감 완료: sellerOrderId=$sellerOrderId, refundAmount=$refundAmount" }
    }

    fun pay(settlementId: UUID): SettlementResponse {
        val lock = redissonClient.getLock("settlement:pay:$settlementId")

        if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
            logger.warn { "정산 지급 락 획득 실패: settlementId=$settlementId" }
            throw IllegalStateException("현재 처리 중인 요청이 있습니다. 잠시 후 다시 시도해주세요.")
        }

        return try {
            logger.info { "정산 지급 락 획득: settlementId=$settlementId" }
            settlementPayExecutor.execute(settlementId)
        } finally {
            lock.unlock()
            logger.info { "정산 지급 락 해제: settlementId=$settlementId" }
        }
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
