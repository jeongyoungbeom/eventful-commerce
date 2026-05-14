package com.eventfulcommerce.settlement.service

import com.eventfulcommerce.settlement.dto.SettlementResponse
import com.eventfulcommerce.settlement.exception.SettlementNotFoundException
import com.eventfulcommerce.settlement.repository.SettlementRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class SettlementPayExecutor(
    private val settlementRepository: SettlementRepository
) {

    @Transactional
    fun execute(settlementId: UUID): SettlementResponse {
        val settlement = settlementRepository.findById(settlementId)
            .orElseThrow { SettlementNotFoundException(settlementId) }
        settlement.pay()
        logger.info { "정산 지급 완료: settlementId=$settlementId, sellerId=${settlement.sellerId}" }
        return SettlementResponse.from(settlement)
    }
}
