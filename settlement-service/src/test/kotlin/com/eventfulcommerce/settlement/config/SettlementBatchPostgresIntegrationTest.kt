package com.eventfulcommerce.settlement.config

import com.eventfulcommerce.common.PaymentRefundedPayload
import com.eventfulcommerce.settlement.domain.SettlementStatus
import com.eventfulcommerce.settlement.domain.entity.Settlement
import com.eventfulcommerce.settlement.repository.SettlementRefundRepository
import com.eventfulcommerce.settlement.repository.SettlementRepository
import com.eventfulcommerce.settlement.service.SettlementPayExecutor
import com.eventfulcommerce.settlement.service.SettlementService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.redisson.api.RedissonClient
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.item.ItemWriter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@Testcontainers
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.show-sql=false"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [SettlementBatchTestConfiguration::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SettlementBatchPostgresIntegrationTest {
    @Autowired private lateinit var launcher: JobLauncher
    @Autowired private lateinit var dailySettlementJob: Job
    @Autowired private lateinit var explorer: JobExplorer
    @Autowired private lateinit var jobRepository: JobRepository
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var settlements: SettlementRepository
    @Autowired private lateinit var receipts: SettlementRefundRepository
    @Autowired private lateinit var service: SettlementService
    @Autowired private lateinit var probe: SettlementBatchWriterProbe

    private val jdbc get() = JdbcTemplate(dataSource)
    private var nextId = 0L

    @BeforeEach
    fun prepareIsolatedDatabase() {
        probe.reset()
        if (!schemaApplied) {
            // 서비스 DB와 무관한 Testcontainers DB에서 실제 제품 migration과 Batch 메타데이터를 사용한다.
            jdbc.execute("DROP TABLE settlement_refund")
            ResourceDatabasePopulator(
                ClassPathResource("db/migration/V1__settlement_refund_idempotency.sql"),
                ClassPathResource("db/migration/V2__settlement_confirmation_index.sql"),
                ClassPathResource("org/springframework/batch/core/schema-postgresql.sql")
            ).execute(dataSource)
            schemaApplied = true
        }
        jdbc.execute("TRUNCATE TABLE settlement_refund, settlements, batch_job_instance CASCADE")
        nextId = 0
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 999, 1000, 1001])
    fun `페이지 경계의 모든 PENDING 정산을 확정한다`(count: Int) {
        createRows(count)
        val execution = runJob()
        assertConfirmed(execution, count)
        assertEquals(count.toLong(), execution.stepExecutions.single().readCount)
        assertEquals(count.toLong(), execution.stepExecutions.single().writeCount)
    }

    @Test
    @DisplayName("동일 생성 시각의 2000건을 ID 순으로 누락 없이 확정한다")
    fun twoThousandPendingRowsAreAllConfirmed() {
        val ids = createRows(2000)
        val execution = runJob()
        assertConfirmed(execution, 2000)
        assertEquals(ids.reversed(), probe.writtenIds)
        assertEquals(2000L, execution.stepExecutions.single().readCount)
    }

    @Test
    fun `UTC 자정 이전만 확정하고 경계 이후와 대상 외 상태는 보존한다`() {
        val older = createRows(1, cutoff.minusSeconds(86_400 * 30)).single()
        val before = createRows(1, cutoff.minusNanos(1000)).single()
        val at = createRows(1, cutoff).single()
        val after = createRows(1, cutoff.plusNanos(1000)).single()
        val otherIds = listOf(SettlementStatus.CONFIRMED, SettlementStatus.PARTIALLY_REFUNDED,
            SettlementStatus.REFUNDED, SettlementStatus.PAID).map { createRows(1, status = it).single() }
        val untouched = (listOf(at, after) + otherIds).associateWith(::row)

        assertEquals(BatchStatus.COMPLETED, runJob().status)

        assertEquals(listOf(older, before), probe.writtenIds)
        assertEquals("CONFIRMED", row(older)["status"])
        assertEquals("CONFIRMED", row(before)["status"])
        untouched.forEach { (id, original) -> assertEquals(original, row(id)) }
    }

    @Test
    fun `같은 날짜 새 실행과 완료 JobInstance 재요청은 확정 효과를 반복하지 않는다`() {
        createRows(1001)
        val parameters = parameters()
        assertConfirmed(launcher.run(dailySettlementJob, parameters), 1001)
        val original = jdbc.queryForList("SELECT * FROM settlements ORDER BY id")
        probe.reset()

        val repeated = runJob()
        assertEquals(BatchStatus.COMPLETED, repeated.status)
        assertEquals(0L, repeated.stepExecutions.single().readCount)
        assertTrue(probe.writtenIds.isEmpty())
        assertEquals(original, jdbc.queryForList("SELECT * FROM settlements ORDER BY id"))
        assertThrows(JobInstanceAlreadyCompleteException::class.java) {
            launcher.run(dailySettlementJob, parameters)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `청크 후반 실패는 해당 갱신과 체크포인트를 롤백하고 같은 작업을 재시작한다`(failedChunk: Int) {
        createRows(2000)
        val writes = AtomicInteger()
        probe.afterWrite = {
            if (writes.incrementAndGet() == failedChunk) error("청크 갱신 후 의도한 테스트 실패")
        }
        val parameters = parameters()
        val failed = launcher.run(dailySettlementJob, parameters)
        assertEquals(BatchStatus.FAILED, failed.status)
        val committed = (failedChunk - 1) * 1000
        assertEquals(committed, count("CONFIRMED"))
        assertEquals(2000 - committed, count("PENDING"))
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM settlements WHERE status = 'PENDING' AND version <> 0", Int::class.java))
        val failedStep = failed.stepExecutions.single()
        val persisted = explorer.getStepExecution(failed.id!!, failedStep.id!!)!!
        assertEquals(committed, persisted.executionContext.getInt("settlementReader.read.count", 0))
        if (committed > 0) {
            val key = persisted.executionContext["settlementReader.start.after"] as Map<*, *>
            assertEquals(UUID(0, committed.toLong()), key["id"])
        }

        probe.reset()
        val restarted = launcher.run(dailySettlementJob, parameters)
        assertEquals(failed.jobInstance.id, restarted.jobInstance.id)
        assertNotEquals(failed.id, restarted.id)
        assertConfirmed(restarted, 2000)
        assertEquals((2000 - committed).toLong(), restarted.stepExecutions.single().readCount)
        assertEquals(UUID(0, committed + 1L), probe.writtenIds.first())
    }

    @ParameterizedTest
    @ValueSource(longs = [5000, 20000])
    fun `조회 뒤 커밋된 부분 및 전액 환불을 배치가 덮어쓰지 않는다`(amount: Long) {
        val id = createRows(1).single()
        val payload = refund(id, amount)
        val pool = Executors.newSingleThreadExecutor()
        try {
            probe.beforeWrite = { pool.submit { service.applyRefund(payload) }.get(15, TimeUnit.SECONDS) }
            assertEquals(BatchStatus.COMPLETED, runJob().status)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertRefunded(id, amount, expectedVersion = 1, wasConfirmed = false)
    }

    @RepeatedTest(3)
    fun `환불이 먼저 잠그면 배치는 기다린 뒤 환불 상태를 보존한다`() {
        val id = createRows(1).single()
        val payload = refund(id)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writing = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val refund = pool.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    settlements.findBySellerOrderIdForUpdate(payload.sellerOrderId)!!
                    locked.countDown()
                    assertTrue(release.await(15, TimeUnit.SECONDS))
                    service.applyRefund(payload)
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            probe.beforeWrite = { writing.countDown() }
            val batch = pool.submit<JobExecution> { runJob() }
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            awaitDatabaseLock()
            assertFalse(batch.isDone)
            release.countDown()
            refund.get(15, TimeUnit.SECONDS)
            assertEquals(BatchStatus.COMPLETED, batch.get(15, TimeUnit.SECONDS).status)
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertRefunded(id, 5000, expectedVersion = 1, wasConfirmed = false)
    }

    @RepeatedTest(3)
    fun `배치가 먼저 잠그면 환불은 확정 커밋 후 최신 금액에서 차감한다`() {
        val id = createRows(1).single()
        val payload = refund(id)
        val updated = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            probe.afterWrite = {
                updated.countDown()
                assertTrue(release.await(15, TimeUnit.SECONDS))
            }
            val batch = pool.submit<JobExecution> { runJob() }
            assertTrue(updated.await(10, TimeUnit.SECONDS))
            val refund = pool.submit { service.applyRefund(payload) }
            awaitDatabaseLock()
            assertFalse(refund.isDone)
            release.countDown()
            assertEquals(BatchStatus.COMPLETED, batch.get(15, TimeUnit.SECONDS).status)
            refund.get(15, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertRefunded(id, 5000, expectedVersion = 2, wasConfirmed = true)
    }

    @Test
    fun `같은 날짜의 두 동시 실행도 각 행을 한 번만 확정한다`() {
        createRows(1001)
        val barrier = CyclicBarrier(2)
        val writes = AtomicInteger()
        probe.beforeWrite = { if (writes.incrementAndGet() <= 2) barrier.await(15, TimeUnit.SECONDS) }
        // 메타데이터 생성의 SERIALIZABLE 경합과 제품 행 갱신 경합을 분리한다.
        val firstExecution = jobRepository.createJobExecution(dailySettlementJob.name, parameters())
        val secondExecution = jobRepository.createJobExecution(dailySettlementJob.name, parameters())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<JobExecution> {
                dailySettlementJob.execute(firstExecution)
                firstExecution
            }
            val second = pool.submit<JobExecution> {
                dailySettlementJob.execute(secondExecution)
                secondExecution
            }
            val executions = listOf(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))
            executions.forEach { assertConfirmed(it, 1001) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `배치도 version을 올려 확정 이전에 읽은 오래된 저장을 차단한다`() {
        val id = createRows(1).single()
        val stale = settlements.findById(id).orElseThrow()
        assertConfirmed(runJob(), 1)
        stale.applyRefund(5000)
        assertThrows(OptimisticLockingFailureException::class.java) { settlements.saveAndFlush(stale) }
        assertEquals(20000L, row(id)["total_amount"])
        assertEquals("CONFIRMED", row(id)["status"])
        assertEquals(0L, receipts.count())
    }

    private fun parameters(): JobParameters = JobParametersBuilder()
        .addString("targetDate", "2026-10-19")
        .addString("execution", UUID.randomUUID().toString())
        .toJobParameters()

    private fun runJob() = launcher.run(dailySettlementJob, parameters())

    private fun createRows(count: Int, createdAt: Instant = cutoff.minusSeconds(1),
                           status: SettlementStatus = SettlementStatus.PENDING): List<UUID> {
        val ids = (1..count).map { UUID(0, ++nextId) }.reversed()
        jdbc.batchUpdate(
            """INSERT INTO settlements (id, payment_id, order_id, seller_order_id, seller_id, user_id,
                total_amount, platform_fee, seller_amount, refunded_amount, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 20000, 2000, 18000, 0, ?, 0, ?)""",
            ids.map { id -> arrayOf(id, UUID(1, id.leastSignificantBits), UUID(2, id.leastSignificantBits),
                UUID(3, id.leastSignificantBits), UUID(4, 1), UUID(5, 1), status.name, Timestamp.from(createdAt)) }
        )
        return ids
    }

    private fun count(status: String) = jdbc.queryForObject(
        "SELECT count(*) FROM settlements WHERE status = ?", Int::class.java, status)!!

    private fun row(id: UUID) = jdbc.queryForMap("SELECT * FROM settlements WHERE id = ?", id)

    private fun assertConfirmed(execution: JobExecution, count: Int) {
        assertEquals(BatchStatus.COMPLETED, execution.status, execution.allFailureExceptions.toString())
        assertEquals(count, count("CONFIRMED"))
        assertEquals(0, count("PENDING"))
        assertEquals(0, jdbc.queryForObject("""SELECT count(*) FROM settlements WHERE
            version <> 1 OR confirmed_at IS NULL OR total_amount <> 20000 OR platform_fee <> 2000
            OR seller_amount <> 18000 OR refunded_amount <> 0""", Int::class.java))
    }

    private fun refund(id: UUID, amount: Long = 5000): PaymentRefundedPayload {
        val settlement = settlements.findById(id).orElseThrow()
        return PaymentRefundedPayload(UUID.randomUUID(), settlement.paymentId, settlement.orderId,
            settlement.sellerOrderId, settlement.sellerId, amount, "배치 경합 환불", cutoff)
    }

    private fun assertRefunded(id: UUID, amount: Long, expectedVersion: Long, wasConfirmed: Boolean) {
        val actual = row(id)
        assertEquals(20000L - amount, actual["total_amount"])
        assertEquals(2000L - amount / 10, actual["platform_fee"])
        assertEquals(18000L - amount * 9 / 10, actual["seller_amount"])
        assertEquals(amount, actual["refunded_amount"])
        assertEquals(if (amount == 20000L) "REFUNDED" else "PARTIALLY_REFUNDED", actual["status"])
        assertEquals(expectedVersion, actual["version"])
        assertEquals(wasConfirmed, actual["confirmed_at"] != null)
        assertEquals(1L, receipts.count())
    }

    private fun awaitDatabaseLock() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val blocked = jdbc.queryForObject("""SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND lower(query) LIKE '%settlements%'""", Int::class.java)!!
            if (blocked > 0) return
            Thread.sleep(20)
        }
        fail<Unit>("실제 PostgreSQL 정산 행 잠금 대기를 관측하지 못했습니다")
    }

    companion object {
        private var schemaApplied = false
        private val cutoff = Instant.parse("2026-10-19T00:00:00Z")

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

// 정상 회귀 테스트 안에서 청크 실패와 실제 DB 잠금 순서를 제어한다. 제품에는 훅을 추가하지 않는다.
class SettlementBatchWriterProbe {
    @Volatile var beforeWrite: () -> Unit = {}
    @Volatile var afterWrite: () -> Unit = {}
    val writtenIds = CopyOnWriteArrayList<UUID>()
    fun reset() {
        beforeWrite = {}
        afterWrite = {}
        writtenIds.clear()
    }
}

@Configuration(proxyBeanMethods = false)
@EnableBatchProcessing
@EntityScan(basePackageClasses = [Settlement::class])
@EnableJpaRepositories(basePackageClasses = [SettlementRepository::class])
@Import(SettlementBatchConfig::class, SettlementService::class, SettlementPayExecutor::class)
class SettlementBatchTestConfiguration {
    @Bean fun redissonClient(): RedissonClient = mockk(relaxed = true)
    @Bean fun batchWriterProbe() = SettlementBatchWriterProbe()

    @Bean
    fun batchWriterPostProcessor(probe: SettlementBatchWriterProbe) = object : BeanPostProcessor {
        @Suppress("UNCHECKED_CAST")
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (beanName != "settlementWriter") return bean
            val writer = bean as ItemWriter<UUID>
            return ItemWriter<UUID> { chunk ->
                probe.beforeWrite()
                writer.write(chunk)
                probe.writtenIds.addAll(chunk.items)
                probe.afterWrite()
            }
        }
    }
}
