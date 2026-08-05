package com.eventfulcommerce.settlement.service

import com.eventfulcommerce.settlement.config.SettlementConfig
import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.domain.entity.Settlement
import com.eventfulcommerce.settlement.repository.SettlementRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit

class SettlementServiceTest {
    private lateinit var settlementRepository: SettlementRepository
    private lateinit var settlementPayExecutor: SettlementPayExecutor
    private lateinit var redissonClient: RedissonClient
    private lateinit var settlementService: SettlementService

    @BeforeEach
    fun setUp() {
        settlementRepository = mockk()
        settlementPayExecutor = mockk()
        redissonClient = mockk(relaxed = true)
        settlementService = SettlementService(
            settlementRepository = settlementRepository,
            settlementConfig = SettlementConfig(platformFeeRate = 0.1),
            settlementPayExecutor = settlementPayExecutor,
            redissonClient = redissonClient
        )
    }

    @Test
    fun `판매자 주문 기준 기존 정산이 있으면 새 정산을 만들지 않고 기존 정산을 반환한다`() {
        val sellerOrderId = UUID.randomUUID()
        val existing = settlementFixture(sellerOrderId = sellerOrderId)
        every { settlementRepository.findBySellerOrderId(sellerOrderId) } returns existing

        val result = settlementService.createSettlement(
            paymentId = UUID.randomUUID(),
            orderId = UUID.randomUUID(),
            sellerOrderId = sellerOrderId,
            sellerId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            totalAmount = 20_000,
            platformFee = 2_000,
            sellerAmount = 18_000
        )

        assertSame(existing, result)
        verify(exactly = 0) { settlementRepository.save(any<Settlement>()) }
    }

    @Test
    fun `기존 정산이 없으면 PENDING 정산을 생성한다`() {
        val sellerOrderId = UUID.randomUUID()
        every { settlementRepository.findBySellerOrderId(sellerOrderId) } returns null
        every { settlementRepository.save(any<Settlement>()) } answers {
            firstArg<Settlement>().also { setIfUninitialized(it, "id", UUID.randomUUID()) }
        }

        val result = settlementService.createSettlement(
            paymentId = UUID.randomUUID(),
            orderId = UUID.randomUUID(),
            sellerOrderId = sellerOrderId,
            sellerId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            totalAmount = 20_000,
            platformFee = 2_000,
            sellerAmount = 18_000
        )

        assertEquals(sellerOrderId, result.sellerOrderId)
        assertEquals(SettlementStatus.PENDING, result.status)
        assertEquals(20_000, result.totalAmount)
        assertEquals(2_000, result.platformFee)
        assertEquals(18_000, result.sellerAmount)
    }

    @Test
    fun `환불 이벤트는 정산 금액과 수수료를 차감하고 부분 환불 상태로 변경한다`() {
        val sellerOrderId = UUID.randomUUID()
        val settlement = settlementFixture(sellerOrderId = sellerOrderId, totalAmount = 20_000, platformFee = 2_000, sellerAmount = 18_000)
        every { settlementRepository.findBySellerOrderId(sellerOrderId) } returns settlement

        settlementService.applyRefund(sellerOrderId, refundAmount = 5_000)

        assertEquals(15_000, settlement.totalAmount)
        assertEquals(1_500, settlement.platformFee)
        assertEquals(13_500, settlement.sellerAmount)
        assertEquals(5_000, settlement.refundedAmount)
        assertEquals(SettlementStatus.PARTIALLY_REFUNDED, settlement.status)
    }

    @Test
    fun `정산 지급은 분산락을 획득한 뒤 executor에 위임한다`() {
        val settlementId = UUID.randomUUID()
        val lock = mockk<RLock>()
        val settlement = settlementFixture(status = SettlementStatus.CONFIRMED)
        every { redissonClient.getLock("settlement:pay:$settlementId") } returns lock
        every { lock.tryLock(5, 10, TimeUnit.SECONDS) } returns true
        every { lock.unlock() } returns Unit
        every { settlementPayExecutor.execute(settlementId) } returns com.eventfulcommerce.settlement.dto.SettlementResponse.from(settlement)

        val result = settlementService.pay(settlementId)

        assertEquals(settlement.id, result.settlementId)
        verify(exactly = 1) { settlementPayExecutor.execute(settlementId) }
        verify(exactly = 1) { lock.unlock() }
    }

    @Test
    fun `refund is retried when settlement prerequisite is not visible yet`() {
        val sellerOrderId = UUID.randomUUID()
        every { settlementRepository.findBySellerOrderId(sellerOrderId) } returns null

        assertThrows(IllegalStateException::class.java) {
            settlementService.applyRefund(sellerOrderId, refundAmount = 5_000)
        }
    }

    private fun settlementFixture(
        sellerOrderId: UUID = UUID.randomUUID(),
        totalAmount: Long = 20_000,
        platformFee: Long = 2_000,
        sellerAmount: Long = 18_000,
        status: SettlementStatus = SettlementStatus.PENDING
    ): Settlement {
        val settlement = Settlement(
            paymentId = UUID.randomUUID(),
            orderId = UUID.randomUUID(),
            sellerOrderId = sellerOrderId,
            sellerId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            totalAmount = totalAmount,
            platformFee = platformFee,
            sellerAmount = sellerAmount,
            status = status
        )
        setIfUninitialized(settlement, "id", UUID.randomUUID())
        return settlement
    }

    private fun setIfUninitialized(target: Any, fieldName: String, value: Any) {
        try {
            if (findField(target, fieldName).get(target) == null) {
                findField(target, fieldName).set(target, value)
            }
        } catch (_: UninitializedPropertyAccessException) {
            findField(target, fieldName).set(target, value)
        } catch (_: NullPointerException) {
            findField(target, fieldName).set(target, value)
        }
    }

    private fun findField(target: Any, fieldName: String): java.lang.reflect.Field {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field
    }
}
