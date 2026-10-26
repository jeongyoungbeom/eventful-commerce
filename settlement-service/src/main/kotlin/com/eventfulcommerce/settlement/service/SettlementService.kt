package com.eventfulcommerce.settlement.service

import com.eventfulcommerce.common.PaymentRefundedPayload
import com.eventfulcommerce.settlement.domain.entity.SettlementRefund
import com.eventfulcommerce.settlement.repository.SettlementRefundRepository
import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.domain.entity.Settlement
import com.eventfulcommerce.settlement.dto.SettlementResponse
import com.eventfulcommerce.settlement.dto.SettlementSummaryResponse
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
    private val settlementRefundRepository: SettlementRefundRepository,
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
    fun applyRefund(payload: PaymentRefundedPayload) {
        val settlement = settlementRepository.findBySellerOrderIdForUpdate(payload.sellerOrderId)
            ?: throw IllegalStateException("Settlement prerequisite not found for refund: sellerOrderId=${payload.sellerOrderId}")

        require(payload.paymentId == settlement.paymentId && payload.orderId == settlement.orderId &&
            payload.sellerId == settlement.sellerId) { "환불의 결제·주문·판매자가 정산과 일치하지 않습니다" }

        settlementRefundRepository.findById(payload.refundId).orElse(null)?.let { receipt ->
            require(receipt.matches(settlement, payload)) { "동일 refundId의 환불 내용이 일치하지 않습니다" }
            return
        }

        settlement.applyRefund(payload.amount)
        // 처리 기록과 정산 변경은 같은 트랜잭션이다. UNIQUE 충돌도 전체 변경을 롤백한다.
        settlementRefundRepository.saveAndFlush(SettlementRefund(settlement, payload))
        logger.info { "정산 환불 차감 완료: refundId=${payload.refundId}, sellerOrderId=${payload.sellerOrderId}" }
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
