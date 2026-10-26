package com.eventfulcommerce.payment.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.common.metrics.EventfulBusinessMetrics
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.payment.controller.PaymentWebhookController
import com.eventfulcommerce.payment.controller.PaymentSimulationController
import com.eventfulcommerce.payment.exception.GlobalExceptionHandler
import com.eventfulcommerce.payment.webhook.PaymentWebhookVerifier
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ResponseStatusException
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import com.eventfulcommerce.payment.domain.PaymentStatus
import com.eventfulcommerce.payment.domain.PaymentWebhookRequest
import com.eventfulcommerce.payment.domain.entity.Payment
import com.eventfulcommerce.payment.domain.entity.PaymentRefund
import com.eventfulcommerce.payment.repository.PaymentRepository
import com.eventfulcommerce.payment.repository.PaymentRefundRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.mock.mockito.SpyBean
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@Testcontainers
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.show-sql=false"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [PaymentConcurrencyTestConfiguration::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentConcurrencyPostgresIntegrationTest {
    @Autowired private lateinit var service: PaymentService
    @Autowired private lateinit var webhook: PaymentWebhookService
    @Autowired private lateinit var payments: PaymentRepository
    @Autowired private lateinit var refunds: PaymentRefundRepository
    @Autowired private lateinit var outbox: OutboxEventRepository
    @Autowired private lateinit var markers: ProcessedEventRepository
    @SpyBean private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager

    @BeforeEach
    fun cleanUp() {
        if (!schemaApplied) {
            JdbcTemplate(dataSource).execute("alter table payment drop constraint if exists uk_payment_pg_tx")
            ResourceDatabasePopulator(
                ClassPathResource("db/migration/V3__payment_concurrency_and_refund_bounds.sql"),
                // Hibernate는 UNIQUE를 이미 생성한다. 아래에서 제거한 뒤 실제 SQL 정의를 적용한다.
                ClassPathResource("db/migration/V4__payment_pg_transaction.sql")
            )
                .execute(dataSource)
            schemaApplied = true
        }
        outbox.deleteAll()
        refunds.deleteAll()
        payments.deleteAll()
        markers.deleteAll()
    }

    @RepeatedTest(3)
    fun `동시 성공 실패 웹훅은 하나의 종결 결과만 커밋한다`() {
        val p = payment()
        val results = concurrently({ webhook.handle(request(p, "SUCCESS")) }, { webhook.handle(request(p, "FAILED")) })
        assertTrue(results.all { it.isSuccess }, results.toString())
        val actual = current(p)
        assertTrue(actual.status in listOf(PaymentStatus.PAYMENT_COMPLETED, PaymentStatus.PAYMENT_FAILED))
        assertEquals(listOf(actual.status.toString()), outbox.findAll().map { it.eventType })
        assertEquals(0, actual.refundedAmount)
        assertEquals(0, refunds.count())
    }

    @RepeatedTest(3)
    fun `동시 성공과 재전달은 완료 이벤트를 하나만 남긴다`() {
        val p = payment()
        val results = concurrently({ webhook.handle(request(p)) }, { webhook.handle(request(p)) })
        assertTrue(results.all { it.isSuccess }, results.toString())
        webhook.handle(request(p))
        assertEquals(PaymentStatus.PAYMENT_COMPLETED, current(p).status)
        assertEquals(1, outbox.count())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `A B와 B A 대기 취소는 최초 사유를 보존하고 각각 한 번 환불한다`(reverse: Boolean) {
        val p = payment()
        val a = cancellation(p, 7_000, "A 최초 취소")
        val b = cancellation(p, 13_000, "B 최초 취소")
        val ordered = if (reverse) listOf(b, a) else listOf(a, b)
        ordered.forEach { service.handleOrderCanceled(message(it)) }
        service.handleOrderCanceled(message(a.copy(reason = "A 중복 취소")))
        service.handleOrderCancellationReconciliation(message(b.copy(reason = "B 대사")))
        val pending = mapper.readValue(current(p).pendingCancellationPayload, Array<OrderCanceledPayload>::class.java)
        assertEquals(setOf("A 최초 취소", "B 최초 취소"), pending.map { it.reason }.toSet())
        assertEquals(2, pending.size)
        assertEquals(0, refunds.count())

        webhook.handle(request(p))
        webhook.handle(request(p))
        ordered.forEach { service.handleOrderCanceled(message(it)) }
        assertRefunds(p, 20_000, 2)
        assertEquals(PaymentStatus.PAYMENT_REFUNDED, current(p).status)
        assertEquals(setOf("A 최초 취소", "B 최초 취소"), refunds.findAll().map { it.reason }.toSet())
        assertNull(current(p).pendingCancellationPayload)
        assertFalse(current(p).cancellationRequested)
        assertEquals(3, outbox.count())
    }

    @RepeatedTest(3)
    fun `서로 다른 판매자의 동시 대기 취소는 모두 남는다`() {
        val p = payment()
        val a = cancellation(p, 7_000)
        val b = cancellation(p, 13_000)
        val results = concurrently({ service.handleOrderCanceled(message(a)) },
            { service.handleOrderCancellationReconciliation(message(b)) })
        assertTrue(results.all { it.isSuccess }, results.toString())
        webhook.handle(request(p))
        assertRefunds(p, 20_000, 2)
        assertEquals(3, outbox.count())
        assertEquals(2, markers.count())
    }

    @RepeatedTest(3)
    fun `웹훅 취소 대사가 경합해도 두 환불과 누적 금액은 유실되지 않는다`() {
        val p = payment()
        val a = cancellation(p, 7_000)
        val b = cancellation(p, 13_000)
        val results = concurrently({ webhook.handle(request(p)) },
            { service.handleOrderCanceled(message(a)) },
            { service.handleOrderCancellationReconciliation(message(b)) })
        assertTrue(results.all { it.isSuccess }, results.toString())
        assertRefunds(p, 20_000, 2)
        assertEquals(3, outbox.count())
        assertEquals(2, markers.count())
        assertNull(current(p).pendingCancellationPayload)
    }

    @RepeatedTest(3)
    fun `동일 판매자의 동시 취소와 대사는 신규 환불을 한 번만 만든다`() {
        val p = payment(PaymentStatus.PAYMENT_COMPLETED)
        val a = cancellation(p, 7_000)
        val results = concurrently({ service.handleOrderCanceled(message(a)) },
            { service.handleOrderCancellationReconciliation(message(a.copy(reason = "대사"))) })
        assertTrue(results.all { it.isSuccess }, results.toString())
        assertRefunds(p, 7_000, 1)
        val original = refundPayloads().first()
        service.handleOrderCancellationReconciliation(message(a.copy(reason = "후속 대사")))
        assertTrue(refundPayloads().all { it == original })
        assertEquals(3, markers.count())
    }

    @Test
    fun `결제 행 잠금으로 웹훅은 취소 커밋까지 실제 DB에서 대기한다`() {
        val p = payment()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contenderReady = CountDownLatch(1)
        val pid = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner = pool.submit {
                tx().execute {
                    payments.findByOrderIdForUpdate(p.orderId)!!
                    service.handleOrderCanceled(message(cancellation(p, 7_000)))
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val contender = pool.submit {
                tx().execute {
                    pid.set(JdbcTemplate(dataSource).queryForObject("select pg_backend_pid()", Int::class.java)!!)
                    contenderReady.countDown()
                    webhook.handle(request(p))
                }
            }
            assertTrue(contenderReady.await(10, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var waiting = false
            while (System.nanoTime() < deadline && !waiting) {
                waiting = JdbcTemplate(dataSource).queryForObject(
                    "select exists(select 1 from pg_stat_activity where pid = ? and wait_event_type = 'Lock')",
                    Boolean::class.java, pid.get())!!
                if (!waiting) Thread.sleep(20)
            }
            assertTrue(waiting, "웹훅 트랜잭션이 실제 PostgreSQL 결제 행 잠금에서 대기해야 한다")
            assertFalse(contender.isDone)
            release.countDown()
            owner.get(15, TimeUnit.SECONDS)
            contender.get(15, TimeUnit.SECONDS)
            assertRefunds(p, 7_000, 1)
            assertEquals(2, outbox.count())
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `실패 결제는 대기 취소를 끝내고 후속 성공도 무시한다`() {
        val p = payment()
        service.handleOrderCanceled(message(cancellation(p, 7_000)))
        webhook.handle(request(p, "FAILED"))
        webhook.handle(request(p))
        assertEquals(PaymentStatus.PAYMENT_FAILED, current(p).status)
        assertNull(current(p).pendingCancellationPayload)
        assertFalse(current(p).cancellationRequested)
        assertEquals(0, refunds.count())
        assertEquals(listOf("PAYMENT_FAILED"), outbox.findAll().map { it.eventType })
    }

    @Test
    fun `취소 eventId 재전달과 다른 eventId 중복은 효과를 늘리지 않는다`() {
        val p = payment(PaymentStatus.PAYMENT_COMPLETED)
        val a = cancellation(p, 7_000)
        val first = message(a)
        service.handleOrderCanceled(first)
        service.handleOrderCanceled(first)
        service.handleOrderCanceled(message(a.copy(reason = "중복")))
        assertRefunds(p, 7_000, 1)
        assertEquals(1, outbox.count())
        assertEquals(2, markers.count())
    }

    @Test
    fun `동시 환불의 합이 한도를 넘으면 하나만 반영하고 실패 marker는 롤백한다`() {
        val p = payment(PaymentStatus.PAYMENT_COMPLETED)
        val results = concurrently({ service.handleOrderCanceled(message(cancellation(p, 15_000))) },
            { service.handleOrderCanceled(message(cancellation(p, 15_000))) })
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is IllegalArgumentException)
        assertRefunds(p, 15_000, 1)
        assertEquals(1, markers.count())
        assertEquals(1, outbox.count())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `대기 취소와 완료 후 환불 모두 0 음수 초과 Long 경계를 거부한다`(reserved: Boolean) {
        val p = payment(if (reserved) PaymentStatus.PAYMENT_RESERVED else PaymentStatus.PAYMENT_COMPLETED)
        val before = current(p)
        listOf(0L, -1L, 20_001L, Long.MAX_VALUE, Long.MIN_VALUE).forEach { amount ->
            val invalid = message(cancellation(p, amount))
            assertThrows(IllegalArgumentException::class.java) { service.handleOrderCanceled(invalid) }
            assertFalse(markers.existsById(invalid.eventId))
        }
        assertEquals(before.status, current(p).status)
        assertEquals(before.version, current(p).version)
        assertNull(current(p).pendingCancellationPayload)
        assertRefunds(p, 0, 0)
        assertEquals(0, outbox.count())
    }

    @Test
    fun `Long 최대 결제도 합산 오버플로 없이 대기 한도와 전액 환불을 보장한다`() {
        val p = payment(amount = Long.MAX_VALUE)
        val a = cancellation(p, Long.MAX_VALUE - 1)
        service.handleOrderCanceled(message(a))
        assertThrows(IllegalArgumentException::class.java) {
            service.handleOrderCanceled(message(cancellation(p, 2)))
        }
        service.handleOrderCanceled(message(cancellation(p, 1)))
        webhook.handle(request(p))
        assertRefunds(p, Long.MAX_VALUE, 2)
        assertEquals(PaymentStatus.PAYMENT_REFUNDED, current(p).status)
        assertEquals(2, markers.count())
    }

    @Test
    fun `대기 취소의 다른 금액 판매자 사용자 입력을 거부하고 기존 집합을 보존한다`() {
        val p = payment()
        val a = cancellation(p, 7_000)
        service.handleOrderCanceled(message(a))
        val before = current(p).pendingCancellationPayload
        val altered = listOf(a.copy(canceledSellerOrders = listOf(a.canceledSellerOrders.single().copy(refundAmount = 6_000))),
            a.copy(canceledSellerOrders = listOf(a.canceledSellerOrders.single().copy(sellerId = UUID.randomUUID()))),
            a.copy(userId = UUID.randomUUID()), cancellation(p, 14_000))
        altered.forEach { invalid ->
            val m = message(invalid)
            assertThrows(IllegalArgumentException::class.java) { service.handleOrderCancellationReconciliation(m) }
            assertFalse(markers.existsById(m.eventId))
            assertEquals(before, current(p).pendingCancellationPayload)
        }
        assertEquals(1, markers.count())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `부분 전액 환불 대사의 잘못된 입력은 결제 환불 marker Outbox를 보존한다`(fullyRefunded: Boolean) {
        val p = payment(PaymentStatus.PAYMENT_COMPLETED)
        val amount = if (fullyRefunded) p.amount else 7_000L
        val originalCancellation = cancellation(p, amount)
        service.handleOrderCanceled(message(originalCancellation))
        val before = current(p)
        val original = refundPayloads().single()
        val originalOutbox = outbox.findAll().associate { it.id to it.payload }
        val seller = originalCancellation.canceledSellerOrders.single()
        val invalidPayloads = listOf(0L, -1L, amount - 1, 20_001L, Long.MAX_VALUE, Long.MIN_VALUE).map {
            originalCancellation.copy(canceledSellerOrders = listOf(seller.copy(refundAmount = it)))
        } + listOf(
            originalCancellation.copy(userId = UUID.randomUUID()),
            originalCancellation.copy(canceledSellerOrders = listOf(seller.copy(sellerId = UUID.randomUUID()))),
            cancellation(p, p.amount - amount + 1)
        )

        invalidPayloads.forEach { invalid ->
            val m = message(invalid).copy(eventType = "ORDER_CANCELLATION_RECONCILIATION_REQUESTED")
            assertThrows(IllegalArgumentException::class.java) { service.handleOrderCancellationReconciliation(m) }
            assertFalse(markers.existsById(m.eventId))
            assertEquals(1, markers.count())
            val after = current(p)
            assertEquals(before.status, after.status)
            assertEquals(before.version, after.version)
            assertEquals(before.cancellationRequested, after.cancellationRequested)
            assertEquals(before.pendingCancellationPayload, after.pendingCancellationPayload)
            assertRefunds(p, amount, 1)
            val stored = refunds.findById(original.refundId).orElseThrow()
            assertEquals(original.orderId, stored.orderId)
            assertEquals(original.sellerOrderId, stored.sellerOrderId)
            assertEquals(original.sellerId, stored.sellerId)
            assertEquals(original.amount, stored.amount)
            assertEquals(original.reason, stored.reason)
            assertEquals(original.refundedAt, stored.refundedAt)
            assertEquals(originalOutbox, outbox.findAll().associate { it.id to it.payload })
        }

        // 거부된 eventId도 정상 내용으로 다시 전달하면 원본 환불을 재발행할 수 있다.
        val retry = message(invalidPayloads.first())
            .copy(eventType = "ORDER_CANCELLATION_RECONCILIATION_REQUESTED")
        assertThrows(IllegalArgumentException::class.java) { service.handleOrderCancellationReconciliation(retry) }
        service.handleOrderCancellationReconciliation(retry.copy(payload = mapper.writeValueAsString(originalCancellation)))
        assertTrue(markers.existsById(retry.eventId))
        assertEquals(listOf(original, original), refundPayloads())
        assertRefunds(p, amount, 1)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `부분 전액 환불 대사는 다른 eventId 사유에도 원본 환불만 재발행한다`(fullyRefunded: Boolean) {
        val p = payment(PaymentStatus.PAYMENT_COMPLETED)
        val amount = if (fullyRefunded) p.amount else 7_000L
        val originalCancellation = cancellation(p, amount, "최초 환불 사유")
        service.handleOrderCanceled(message(originalCancellation))
        val original = refundPayloads().single()
        val before = current(p)
        val replay = message(originalCancellation.copy(reason = "다른 대사 사유"))
            .copy(eventType = "ORDER_CANCELLATION_RECONCILIATION_REQUESTED")

        service.handleOrderCancellationReconciliation(replay)
        service.handleOrderCancellationReconciliation(replay)
        service.handleOrderCancellationReconciliation(replay.copy(eventId = UUID.randomUUID()))

        assertEquals(List(3) { original }, refundPayloads())
        assertEquals(3, outbox.count())
        assertEquals(3, markers.count())
        assertRefunds(p, amount, 1)
        val after = current(p)
        assertEquals(before.status, after.status)
        assertEquals(before.version, after.version)
        assertEquals(if (fullyRefunded) PaymentStatus.PAYMENT_REFUNDED else PaymentStatus.PAYMENT_PARTIALLY_REFUNDED,
            after.status)
    }

    @Test
    fun `뒤 환불 실패는 앞 환불 결제 Outbox marker도 롤백하고 같은 eventId 재시도할 수 있다`() {
        val p = payment(PaymentStatus.PAYMENT_COMPLETED)
        val a = cancellation(p, 7_000)
        val b = cancellation(p, 14_000)
        val invalid = message(a.copy(canceledSellerOrders = a.canceledSellerOrders + b.canceledSellerOrders))
        assertThrows(IllegalArgumentException::class.java) { service.handleOrderCanceled(invalid) }
        assertEquals(PaymentStatus.PAYMENT_COMPLETED, current(p).status)
        assertRefunds(p, 0, 0)
        assertEquals(0, outbox.count())
        assertFalse(markers.existsById(invalid.eventId))
        service.handleOrderCanceled(invalid.copy(payload = mapper.writeValueAsString(a)))
        assertRefunds(p, 7_000, 1)
        assertTrue(markers.existsById(invalid.eventId))
    }

    @Test
    fun `완료 대기 환불 flush 후 예외는 웹훅과 취소 집합 모두 롤백한다`() {
        val p = payment()
        service.handleOrderCanceled(message(cancellation(p, 7_000)))
        service.handleOrderCanceled(message(cancellation(p, 13_000)))
        val before = current(p)
        assertThrows(IllegalStateException::class.java) {
            tx().execute<Unit> {
                webhook.handle(request(p))
                entityManager.flush()
                throw IllegalStateException("flush 이후 로컬 실패")
            }
        }
        assertEquals(PaymentStatus.PAYMENT_RESERVED, current(p).status)
        assertNull(current(p).pgTxId)
        assertEquals(before.pendingCancellationPayload, current(p).pendingCancellationPayload)
        assertEquals(before.version, current(p).version)
        assertRefunds(p, 0, 0)
        assertEquals(0, outbox.count())
        assertEquals(2, markers.count())
        webhook.handle(request(p))
        assertRefunds(p, 20_000, 2)
    }

    @Test
    fun `환불 직렬화의 checked 예외도 웹훅 서비스 프록시에서 전체 롤백한다`() {
        val p = payment()
        service.handleOrderCanceled(message(cancellation(p, 7_000)))
        val before = current(p)
        doThrow(object : JsonProcessingException("환불 JSON 작성 실패") {}).whenever(mapper)
            .writeValueAsString(any<PaymentRefundedPayload>())

        assertThrows(JsonProcessingException::class.java) { webhook.handle(request(p)) }
        assertEquals(PaymentStatus.PAYMENT_RESERVED, current(p).status)
        assertNull(current(p).pgTxId)
        assertEquals(before.pendingCancellationPayload, current(p).pendingCancellationPayload)
        assertEquals(before.version, current(p).version)
        assertRefunds(p, 0, 0)
        assertEquals(0, outbox.count())
        assertEquals(1, markers.count())
    }

    @Test
    fun `대기 취소 flush 후 예외는 집합과 marker를 함께 롤백한다`() {
        val p = payment()
        val m = message(cancellation(p, 7_000))
        assertThrows(IllegalStateException::class.java) {
            tx().execute<Unit> {
                service.handleOrderCanceled(m)
                entityManager.flush()
                throw IllegalStateException("flush 이후 로컬 실패")
            }
        }
        assertNull(current(p).pendingCancellationPayload)
        assertFalse(current(p).cancellationRequested)
        assertFalse(markers.existsById(m.eventId))
        service.handleOrderCanceled(m)
        webhook.handle(request(p))
        assertRefunds(p, 7_000, 1)
    }

    @Test
    fun `저장소의 늦은 writer와 실제 금액 UNIQUE 제약을 검증한다`() {
        val p = payment()
        val stale = current(p)
        webhook.handle(request(p))
        stale.status = PaymentStatus.PAYMENT_FAILED
        assertThrows(OptimisticLockingFailureException::class.java) { payments.saveAndFlush(stale) }
        assertEquals(PaymentStatus.PAYMENT_COMPLETED, current(p).status)
        val jdbc = JdbcTemplate(dataSource)
        listOf(-1L, 20_001L).forEach {
            assertThrows(DataIntegrityViolationException::class.java) {
                jdbc.update("update payment set refunded_amount = ? where id = ?", it, p.id)
            }
        }
        val a = cancellation(p, 7_000)
        service.handleOrderCanceled(message(a))
        val seller = a.canceledSellerOrders.single()
        assertThrows(DataIntegrityViolationException::class.java) {
            refunds.saveAndFlush(PaymentRefund(current(p), p.orderId, seller.sellerOrderId, seller.sellerId, 7_000, "중복"))
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("update payment_refund set amount = 0 where payment_id = ?", p.id)
        }
        assertRefunds(p, 7_000, 1)
    }

    @Test
    fun `없는 결제의 취소 marker는 롤백하고 결제 생성 후 같은 입력이 성공한다`() {
        val p = Payment(UUID.randomUUID(), UUID.randomUUID(), PaymentStatus.PAYMENT_RESERVED, 20_000, "[]")
        val m = message(cancellation(p, 7_000))
        assertThrows(IllegalStateException::class.java) { service.handleOrderCanceled(m) }
        assertFalse(markers.existsById(m.eventId))
        payments.saveAndFlush(p)
        service.handleOrderCanceled(m)
        webhook.handle(request(p))
        assertRefunds(p, 7_000, 1)
    }

    @RepeatedTest(3)
    fun `다른 결제의 같은 거래 ID 동시 수신은 하나만 커밋한다`() {
        val a = payment()
        val b = payment()
        val pgTxId = "PG-shared-${UUID.randomUUID()}"
        val beforeWrite = CyclicBarrier(2)
        val calls = AtomicInteger()
        doAnswer { invocation ->
            if (calls.incrementAndGet() <= 2) beforeWrite.await(10, TimeUnit.SECONDS)
            invocation.callRealMethod()
        }.whenever(mapper).writeValueAsString(any<PaymentCompletedPayload>())
        val results = concurrently(
            { webhook.handle(request(a).copy(pgTxId = pgTxId)) },
            { webhook.handle(request(b).copy(pgTxId = pgTxId)) }
        )
        assertEquals(1, results.count { it.isSuccess }, results.toString())
        val error = results.single { it.isFailure }.exceptionOrNull()
        assertTrue(error is DataIntegrityViolationException, error.toString())
        val saved = listOf(current(a), current(b))
        val winner = saved.single { it.pgTxId == pgTxId }
        val loser = saved.single { it.pgTxId == null }
        assertEquals(PaymentStatus.PAYMENT_COMPLETED, winner.status)
        assertEquals(PaymentStatus.PAYMENT_RESERVED, loser.status)
        assertEquals(0, loser.version)
        assertEquals(1, outbox.count())
        assertEquals(0, refunds.count())
        assertThrows(IllegalArgumentException::class.java) {
            webhook.handle(request(loser).copy(pgTxId = pgTxId))
        }
        webhook.handle(request(loser))
        assertEquals(2, outbox.count())
    }

    @Test
    fun `검증 거절은 저장된 거래와 상태 version Outbox를 보존한다`() {
        val p = payment()
        webhook.handle(request(p))
        val before = current(p)
        listOf(request(p).copy(amount = null), request(p).copy(amount = 1),
            request(p).copy(result = "INVALID"), request(p).copy(pgTxId = "PG-other")).forEach {
            assertThrows(IllegalArgumentException::class.java) { webhook.handle(it) }
        }
        val after = current(p)
        assertEquals(before.pgTxId, after.pgTxId)
        assertEquals(before.status, after.status)
        assertEquals(before.version, after.version)
        assertEquals(1, outbox.count())
        assertEquals(0, refunds.count())
    }

    @Test
    fun `실제 거래 ID DB 제약은 직접 writer의 재사용과 빈 값을 차단한다`() {
        val a = payment()
        val b = payment()
        webhook.handle(request(a))
        val jdbc = JdbcTemplate(dataSource)
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("update payment set pg_tx_id = ? where id = ?", request(a).pgTxId, b.id)
        }
        listOf("", "   ", " PG-spaced", "x".repeat(201)).forEach {
            assertThrows(DataIntegrityViolationException::class.java) {
                jdbc.update("update payment set pg_tx_id = ? where id = ?", it, b.id)
            }
        }
        assertNull(current(b).pgTxId)
        assertEquals(PaymentStatus.PAYMENT_RESERVED, current(b).status)
        assertEquals(1, outbox.count())
    }

    @Test
    fun `로컬 HTTP 시뮬레이션의 인증 검증과 실제 DB 완료 중복 차단을 연결한다`() {
        val p = payment()
        val mvc = MockMvcBuilders.standaloneSetup(PaymentSimulationController(webhook, "local-test-token"))
            .setControllerAdvice(GlobalExceptionHandler()).build()
        val body = mapper.writeValueAsString(request(p))
        mvc.perform(post("/payments/simulation/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/payments/simulation/webhook").contentType(MediaType.APPLICATION_JSON)
            .header("X-Payment-Simulation-Token", "wrong").content(body)).andExpect(status().isUnauthorized)
        mvc.perform(post("/payments/simulation/webhook").contentType(MediaType.APPLICATION_JSON)
            .header("X-Payment-Simulation-Token", "local-test-token")
            .content(mapper.writeValueAsString(request(p).copy(amount = null)))).andExpect(status().isBadRequest)
        assertEquals(PaymentStatus.PAYMENT_RESERVED, current(p).status)
        assertNull(current(p).pgTxId)
        assertEquals(0, outbox.count())
        repeat(2) {
            mvc.perform(post("/payments/simulation/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-Payment-Simulation-Token", "local-test-token").content(body)).andExpect(status().isOk)
        }
        assertEquals(PaymentStatus.PAYMENT_COMPLETED, current(p).status)
        assertEquals(request(p).pgTxId, current(p).pgTxId)
        assertEquals(1, outbox.count())
    }

    @Test
    fun `실제 수신 HTTP 검증 계약을 통과한 결과만 DB에 반영한다`() {
        val p = payment()
        val factory = StaticListableBeanFactory()
        factory.addBean("testVerifier", PaymentWebhookVerifier { rawBody, headers ->
            // 테스트 전용 계약 구현이다. 실제 PG 인증 구현으로 등록하지 않는다.
            if (headers.getFirst("X-Provider-Test") != "test-authenticated") {
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "테스트 인증 거부")
            }
            mapper.readValue(rawBody, PaymentWebhookRequest::class.java)
        })
        val mvc = MockMvcBuilders.standaloneSetup(PaymentWebhookController(webhook,
            factory.getBeanProvider(PaymentWebhookVerifier::class.java)))
            .setControllerAdvice(GlobalExceptionHandler()).build()
        val body = mapper.writeValueAsString(request(p))
        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)
        assertNull(current(p).pgTxId)
        assertEquals(0, outbox.count())
        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
            .header("X-Provider-Test", "test-authenticated")
            .content(mapper.writeValueAsString(request(p).copy(amount = p.amount - 1)))).andExpect(status().isBadRequest)
        assertEquals(PaymentStatus.PAYMENT_RESERVED, current(p).status)
        assertNull(current(p).pgTxId)
        assertEquals(0, outbox.count())
        mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
            .header("X-Provider-Test", "test-authenticated").content(body)).andExpect(status().isOk)
        assertEquals(PaymentStatus.PAYMENT_COMPLETED, current(p).status)
        assertEquals(request(p).pgTxId, current(p).pgTxId)
        assertEquals(1, outbox.count())
    }

    private fun payment(status: PaymentStatus = PaymentStatus.PAYMENT_RESERVED, amount: Long = 20_000): Payment =
        payments.saveAndFlush(Payment(UUID.randomUUID(), UUID.randomUUID(), status, amount, "[]"))

    private fun current(p: Payment) = tx().execute { payments.findByOrderId(p.orderId) }!!
    private fun request(p: Payment, result: String = "SUCCESS") = PaymentWebhookRequest(p.orderId, result, "PG-${p.id}", p.amount)
    private fun cancellation(p: Payment, amount: Long, reason: String = "사용자 취소") = OrderCanceledPayload(
        p.orderId, p.userId, reason, listOf(OrderCanceledSellerPayload(UUID.randomUUID(), UUID.randomUUID(), amount)))
    private fun message(payload: OrderCanceledPayload) = OutboxEventMessage(UUID.randomUUID(), "ORDER", payload.orderId,
        "ORDER_CANCELED", Instant.now(), mapper.writeValueAsString(payload))
    private fun refundPayloads() = outbox.findAll().filter { it.eventType == "PAYMENT_REFUNDED" }
        .map { mapper.readValue(it.payload, PaymentRefundedPayload::class.java) }
    private fun assertRefunds(p: Payment, amount: Long, count: Long) {
        assertEquals(amount, current(p).refundedAmount)
        assertEquals(count, refunds.count())
        assertEquals(amount, refunds.findAll().sumOf { it.amount })
    }
    private fun tx() = TransactionTemplate(transactionManager)
    private fun concurrently(vararg actions: () -> Unit): List<Result<Unit>> {
        val barrier = CyclicBarrier(actions.size)
        val pool = Executors.newFixedThreadPool(actions.size)
        return try {
            actions.map { action -> pool.submit<Result<Unit>> {
                runCatching { tx().execute {
                    JdbcTemplate(dataSource).queryForObject("select 1", Int::class.java)
                    barrier.await(10, TimeUnit.SECONDS)
                    action()
                }; Unit }
            } }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    companion object {
        private var schemaApplied = false
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @DynamicPropertySource @JvmStatic fun registerPostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = [Payment::class, ProcessedEvent::class, OutboxEvent::class])
@EnableJpaRepositories(basePackageClasses = [PaymentRepository::class, ProcessedEventRepository::class, OutboxEventRepository::class])
@Import(PaymentService::class, PaymentWebhookService::class, IdempotencyHandler::class)
class PaymentConcurrencyTestConfiguration {
    @Bean fun businessMetrics(): EventfulBusinessMetrics = mock()
    @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
}
