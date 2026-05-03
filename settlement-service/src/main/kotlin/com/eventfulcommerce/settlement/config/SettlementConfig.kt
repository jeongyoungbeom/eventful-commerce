package com.eventfulcommerce.settlement.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "settlement")
data class SettlementConfig(
    var platformFeeRate: Double = 0.10,
    var batchCron: String = "0 0 0 * * *"
) {
    @PostConstruct
    fun validate() {
        require(platformFeeRate in 0.0..1.0) { "플랫폼 수수료율은 0.0~1.0 사이여야 합니다: $platformFeeRate" }
    }

    fun calculatePlatformFee(amount: Long): Long = (amount * platformFeeRate).toLong()
    fun calculateSellerAmount(amount: Long): Long = amount - calculatePlatformFee(amount)
}
