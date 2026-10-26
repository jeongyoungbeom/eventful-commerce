package com.eventfulcommerce.settlement.service

import com.eventfulcommerce.common.IdempotencyHandler
import com.eventfulcommerce.common.OrderCanceledPayload
import com.eventfulcommerce.common.OrderCanceledSellerPayload
import com.eventfulcommerce.common.OutboxEventMessage
import com.eventfulcommerce.common.PaymentRefundedPayload
import com.eventfulcommerce.common.ProcessedEvent
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.common.metrics.EventfulBusinessMetrics
import com.eventfulcommerce.payment.domain.PaymentStatus
import com.eventfulcommerce.payment.domain.entity.Payment
import com.eventfulcommerce.payment.repository.PaymentRepository
import com.eventfulcommerce.payment.repository.PaymentRefundRepository
import com.eventfulcommerce.payment.service.PaymentService
import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.domain.entity.Settlement
import com.eventfulcommerce.settlement.domain.entity.SettlementRefund
import com.eventfulcommerce.settlement.message.PaymentEventsConsumer
import com.eventfulcommerce.settlement.repository.SettlementRefundRepository
import com.eventfulcommerce.settlement.repository.SettlementRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Testcontainers
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.show-sql=false"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [SettlementRefundTestConfiguration::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SettlementRefundPostgresIntegrationTest {
    @Autowired private lateinit var paymentService: PaymentService
    @Autowired private lateinit var payments: PaymentRepository
    @Autowired private lateinit var paymentRefunds: PaymentRefundRepository
    @Autowired private lateinit var outbox: OutboxEventRepository
    @Autowired private lateinit var service: SettlementService
    @Autowired private lateinit var consumer: PaymentEventsConsumer
    @Autowired private lateinit var idempotencyHandler: IdempotencyHandler
    @Autowired private lateinit var settlements: SettlementRepository
    @Autowired private lateinit var receipts: SettlementRefundRepository
    @Autowired private lateinit var events: ProcessedEventRepository
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private fun applyActualMigration() {
        // 정산 기준 테이블은 Hibernate로 만들고 새 처리 기록은 제품 SQL로 생성한다.
        JdbcTemplate(dataSource).execute("DROP TABLE settlement_refund")
        ResourceDatabasePopulator(ClassPathResource("db/migration/V1__settlement_refund_idempotency.sql"))
            .execute(dataSource)
    }

    @BeforeEach
    fun cleanUp() {
        if (!schemaApplied) {
            applyActualMigration()
            schemaApplied = true
        }
        outbox.deleteAll()
        paymentRefunds.deleteAll()
        payments.deleteAll()
        receipts.deleteAll()
        settlements.deleteAll()
        events.deleteAll()
    }

    @Test
    fun `같은 eventId 재전달과 다른 eventId 환불 재발행은 한 번만 차감한다`() {
        val settlement = createSettlement()
        val payload = refund(settlement)
        val eventId = UUID.randomUUID()
        val original = message(eventId, payload)
        consumer.consume(original)
        consumer.consume(original)
        consumer.consume(message(UUID.randomUUID(), payload))

        assertAmounts(settlement, 15_000, 1_500, 13_500, 5_000)
        assertEquals(1, receipts.count())
        assertEquals(2, events.count())
    }

    @Test
    fun `커밋한 결제 환불을 별도 트랜잭션에서 재조회 재발행해도 정산은 한 번만 차감한다`() {
        val payment = payments.saveAndFlush(Payment(
            orderId = UUID.randomUUID(), userId = UUID.randomUUID(),
            status = PaymentStatus.PAYMENT_COMPLETED, amount = 20_000, sellerOrdersJson = "[]"
        ))
        val settlement = service.createSettlement(payment.id, payment.orderId, UUID.randomUUID(),
            UUID.randomUUID(), payment.userId, 20_000, 2_000, 18_000)
        val cancellation = OrderCanceledPayload(payment.orderId, payment.userId, "부분 취소",
            listOf(OrderCanceledSellerPayload(settlement.sellerOrderId, settlement.sellerId, 5_000)))
        fun cancellationMessage(type: String) = OutboxEventMessage(UUID.randomUUID(), "ORDER",
            payment.orderId, type, Instant.now(), mapper.writeValueAsString(cancellation))

        // 테스트 바깥 트랜잭션이 없다. 서비스 프록시 호출마다 커밋하고 영속성 컨텍스트를 닫는다.
        paymentService.handleOrderCanceled(cancellationMessage("ORDER_CANCELED"))
        val first = outbox.findAll().single()
        val original = mapper.readValue(first.payload, PaymentRefundedPayload::class.java)
        assertEquals(0, original.refundedAt.nano % 1_000)
        val storedTime = TransactionTemplate(transactionManager).execute {
            paymentRefunds.findById(original.refundId).orElseThrow().refundedAt
        }
        assertEquals(original.refundedAt, storedTime)
        consumer.consume(message(first.id, original))

        paymentService.handleOrderCancellationReconciliation(
            cancellationMessage("ORDER_CANCELLATION_RECONCILIATION_REQUESTED"))
        val republished = outbox.findAll().single { it.id != first.id }
        val replay = mapper.readValue(republished.payload, PaymentRefundedPayload::class.java)
        assertNotEquals(first.id, republished.id)
        assertEquals(original, replay)
        consumer.consume(message(republished.id, replay))
        consumer.consume(message(first.id, original))

        assertAmounts(settlement, 15_000, 1_500, 13_500, 5_000)
        assertEquals(1, paymentRefunds.count())
        assertEquals(1, receipts.count())
        assertTrue(events.existsById(first.id))
        assertTrue(events.existsById(republished.id))
        assertEquals(4, events.count()) // 취소·대사 입력 2개와 정산 환불 입력 2개
    }

    @RepeatedTest(3)
    fun `다른 eventId로 동시에 받은 동일 환불은 한 번만 반영한다`() {
        val settlement = createSettlement()
        val payload = refund(settlement)
        val results = concurrently(listOf(payload, payload))

        assertTrue(results.all { it.isSuccess }, results.toString())
        assertAmounts(settlement, 15_000, 1_500, 13_500, 5_000)
        assertEquals(1, receipts.count())
        assertEquals(2, events.count())
    }

    @RepeatedTest(3)
    fun `서로 다른 환불이 정산에서 경합해도 누적 차감이 유실되지 않는다`() {
        val settlement = createSettlement()
        val results = concurrently(listOf(refund(settlement, 7_000), refund(settlement, 3_000)))

        assertTrue(results.all { it.isSuccess }, results.toString())
        assertAmounts(settlement, 10_000, 1_000, 9_000, 10_000)
        assertEquals(2, receipts.count())
        assertEquals(2, events.count())
    }

    @Test
    fun `잔여 한도를 함께 초과하는 동시 환불은 하나만 반영한다`() {
        val settlement = createSettlement()
        val results = concurrently(listOf(refund(settlement, 15_000), refund(settlement, 15_000)))

        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is IllegalArgumentException)
        assertAmounts(settlement, 5_000, 500, 4_500, 15_000)
        assertEquals(1, receipts.count())
        assertEquals(1, events.count())
    }

    @Test
    fun `동일 refundId를 서로 다른 정산에서 경합해도 기록과 금액은 하나만 반영한다`() {
        val first = createSettlement()
        val second = createSettlement()
        val payload = refund(first)
        val results = concurrently(listOf(payload, refund(second).copy(refundId = payload.refundId)))

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(35_000, settlements.findAll().sumOf { it.totalAmount })
        assertEquals(5_000, settlements.findAll().sumOf { it.refundedAmount })
        assertEquals(1, receipts.count())
        assertEquals(1, events.count())
    }

    @Test
    fun `동일 refundId의 금액 이유 시각 및 정산 식별자 불일치를 거부한다`() {
        val settlement = createSettlement()
        val other = createSettlement()
        val payload = refund(settlement)
        consumer.consume(message(UUID.randomUUID(), payload))
        val altered = listOf(
            payload.copy(amount = 6_000), payload.copy(reason = "다른 취소"),
            payload.copy(refundedAt = payload.refundedAt.plusNanos(1)),
            payload.copy(paymentId = UUID.randomUUID()), payload.copy(orderId = UUID.randomUUID()),
            payload.copy(sellerId = UUID.randomUUID()), refund(other).copy(refundId = payload.refundId)
        )
        altered.forEach { invalid ->
            val eventId = UUID.randomUUID()
            assertThrows(IllegalArgumentException::class.java) { consumer.consume(message(eventId, invalid)) }
            assertFalse(events.existsById(eventId))
        }

        assertAmounts(settlement, 15_000, 1_500, 13_500, 5_000)
        assertAmounts(other, 20_000, 2_000, 18_000, 0)
        assertEquals(1, receipts.count())
        assertEquals(1, events.count())
    }

    @Test
    fun `0 음수 잔여 초과 및 Long 경계 금액은 처리 기록 없이 거부한다`() {
        val settlement = createSettlement()
        listOf(0L, -1L, 20_001L, Long.MAX_VALUE, Long.MIN_VALUE).forEach { amount ->
            assertThrows(IllegalArgumentException::class.java) {
                consumer.consume(message(UUID.randomUUID(), refund(settlement, amount)))
            }
        }

        assertAmounts(settlement, 20_000, 2_000, 18_000, 0)
        assertEquals(0, receipts.count())
        assertEquals(0, events.count())
    }

    @Test
    fun `부분 환불의 반올림 잔액은 마지막 환불에서 소진되고 재발행은 허용된다`() {
        val settlement = createSettlement(total = 10, fee = 1)
        consumer.consume(message(UUID.randomUUID(), refund(settlement, 1)))
        assertAmounts(settlement, 9, 1, 8, 1)
        val last = refund(settlement, 9)
        consumer.consume(message(UUID.randomUUID(), last))
        consumer.consume(message(UUID.randomUUID(), last))

        assertAmounts(settlement, 0, 0, 0, 10)
        assertEquals(SettlementStatus.REFUNDED, settlements.findById(settlement.id).orElseThrow().status)
        assertThrows(IllegalArgumentException::class.java) {
            consumer.consume(message(UUID.randomUUID(), refund(settlement, 1)))
        }
        assertEquals(2, receipts.count())
        assertEquals(3, events.count())
    }

    @Test
    fun `정산에 기록된 수수료 비율로 환불하고 큰 금액의 곱셈도 넘치지 않는다`() {
        val custom = createSettlement(fee = 4_000)
        service.applyRefund(refund(custom))
        assertAmounts(custom, 15_000, 3_000, 12_000, 5_000)

        val large = createSettlement(total = Long.MAX_VALUE, fee = Long.MAX_VALUE / 10)
        val firstAmount = Long.MAX_VALUE / 2
        service.applyRefund(refund(large, firstAmount))
        service.applyRefund(refund(large, Long.MAX_VALUE - firstAmount))
        assertAmounts(large, 0, 0, 0, Long.MAX_VALUE)
    }

    @Test
    fun `트랜잭션 후반 예외는 eventId 처리 기록 환불 기록과 정산을 함께 롤백한다`() {
        val settlement = createSettlement()
        val eventId = UUID.randomUUID()
        val payload = refund(settlement)

        assertThrows(IllegalStateException::class.java) {
            idempotencyHandler.executeIdempotent(eventId) {
                service.applyRefund(payload)
                assertEquals(1, receipts.count())
                throw IllegalStateException("환불 저장 후 후속 작업 실패")
            }
        }
        assertAmounts(settlement, 20_000, 2_000, 18_000, 0)
        assertEquals(0, receipts.count())
        assertFalse(events.existsById(eventId))

        consumer.consume(message(eventId, payload))
        assertAmounts(settlement, 15_000, 1_500, 13_500, 5_000)
        assertEquals(1, receipts.count())
        assertTrue(events.existsById(eventId))
    }

    @Test
    fun `PostgreSQL refundId UNIQUE 충돌은 선행 금액 갱신까지 롤백한다`() {
        val settlement = createSettlement()
        val payload = refund(settlement)
        service.applyRefund(payload)

        assertThrows(DataIntegrityViolationException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                val locked = settlements.findBySellerOrderIdForUpdate(settlement.sellerOrderId)!!
                locked.applyRefund(payload.amount)
                receipts.saveAndFlush(SettlementRefund(locked, payload))
            }
        }
        assertAmounts(settlement, 15_000, 1_500, 13_500, 5_000)
        assertEquals(1, receipts.count())
    }

    @Test
    fun `제품 SQL의 금액 CHECK와 정산 FK가 잘못된 처리 기록을 거부한다`() {
        val settlement = createSettlement()
        val jdbc = JdbcTemplate(dataSource)
        fun insert(settlementId: UUID, amount: Long) = jdbc.update(
            "INSERT INTO settlement_refund (refund_id, settlement_id, amount, reason, refunded_at) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), settlementId, amount, "스키마 검증", Instant.now().toString()
        )

        listOf(0L, -1L).forEach { amount ->
            assertThrows(DataIntegrityViolationException::class.java) { insert(settlement.id, amount) }
        }
        assertThrows(DataIntegrityViolationException::class.java) { insert(UUID.randomUUID(), 1) }
        assertEquals(0, receipts.count())
    }

    @Test
    fun `정산이 없으면 전체 이벤트를 롤백하고 정산 생성 후 같은 이벤트를 재시도한다`() {
        val paymentId = UUID.randomUUID()
        val orderId = UUID.randomUUID()
        val sellerOrderId = UUID.randomUUID()
        val sellerId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val payload = PaymentRefundedPayload(UUID.randomUUID(), paymentId, orderId, sellerOrderId,
            sellerId, 5_000, "부분 취소", Instant.now())

        assertThrows(IllegalStateException::class.java) { consumer.consume(message(eventId, payload)) }
        assertEquals(0, receipts.count())
        assertFalse(events.existsById(eventId))
        val settlement = service.createSettlement(paymentId, orderId, sellerOrderId, sellerId,
            UUID.randomUUID(), 20_000, 2_000, 18_000)
        consumer.consume(message(eventId, payload))

        assertAmounts(settlement, 15_000, 1_500, 13_500, 5_000)
        assertTrue(events.existsById(eventId))
    }

    @Test
    fun `환불 전 읽은 정산의 늦은 저장은 환불 금액을 덮어쓰지 못한다`() {
        val settlement = createSettlement()
        val stale = settlements.findById(settlement.id).orElseThrow()
        service.applyRefund(refund(settlement))
        stale.confirm()

        assertThrows(OptimisticLockingFailureException::class.java) { settlements.saveAndFlush(stale) }
        assertAmounts(settlement, 15_000, 1_500, 13_500, 5_000)
        assertEquals(SettlementStatus.PARTIALLY_REFUNDED, settlements.findById(settlement.id).orElseThrow().status)
    }

    private fun createSettlement(total: Long = 20_000, fee: Long = 2_000): Settlement =
        service.createSettlement(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), total, fee, total - fee)

    private fun refund(settlement: Settlement, amount: Long = 5_000) = PaymentRefundedPayload(
        UUID.randomUUID(), settlement.paymentId, settlement.orderId, settlement.sellerOrderId,
        settlement.sellerId, amount, "부분 취소", Instant.parse("2026-10-07T12:34:56.123456789Z")
    )

    private fun message(eventId: UUID, payload: PaymentRefundedPayload): String = mapper.writeValueAsString(
        OutboxEventMessage(eventId, "PAYMENT_REFUND", payload.paymentId, "PAYMENT_REFUNDED",
            Instant.now(), mapper.writeValueAsString(payload))
    )

    private fun assertAmounts(settlement: Settlement, total: Long, fee: Long, seller: Long, refunded: Long) {
        val actual = settlements.findById(settlement.id).orElseThrow()
        assertEquals(total, actual.totalAmount)
        assertEquals(fee, actual.platformFee)
        assertEquals(seller, actual.sellerAmount)
        assertEquals(refunded, actual.refundedAmount)
        assertEquals(total - fee, actual.sellerAmount)
    }

    private fun concurrently(payloads: List<PaymentRefundedPayload>): List<Result<Unit>> {
        val barrier = CyclicBarrier(payloads.size)
        val executor = Executors.newFixedThreadPool(payloads.size)
        return try {
            payloads.map { payload ->
                executor.submit<Result<Unit>> {
                    runCatching {
                        idempotencyHandler.executeIdempotent(UUID.randomUUID()) {
                            barrier.await(10, TimeUnit.SECONDS)
                            service.applyRefund(payload)
                        }
                        Unit
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    companion object {
        private var schemaApplied = false

        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource @JvmStatic
        fun registerPostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = [Settlement::class, ProcessedEvent::class, Payment::class])
@EnableJpaRepositories(basePackageClasses = [SettlementRepository::class, ProcessedEventRepository::class, PaymentRepository::class])
@Import(SettlementService::class, SettlementPayExecutor::class, IdempotencyHandler::class, PaymentEventsConsumer::class, PaymentService::class)
class SettlementRefundTestConfiguration {
    @Bean fun businessMetrics(): EventfulBusinessMetrics = mockk(relaxed = true)
    @Bean fun redissonClient(): RedissonClient = mockk(relaxed = true)
    @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
}
