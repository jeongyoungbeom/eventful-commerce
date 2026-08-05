package com.eventfulcommerce.order.repository

import com.eventfulcommerce.order.domain.entity.OrderRequestIdempotency
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.redisson.spring.starter.RedissonAutoConfigurationV2
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = [OrderIdempotencyRepositoryTestApplication::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderRequestIdempotencyPostgresIntegrationTest {
    @Autowired
    private lateinit var repository: OrderRequestIdempotencyRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun cleanUp() {
        repository.deleteAll()
    }

    @Test
    @Transactional
    fun `PostgreSQL unique claim allows one request and stores its response once`() {
        val userId = UUID.randomUUID()
        val key = "order-request-001"
        val id = UUID.nameUUIDFromBytes("$userId:$key".toByteArray())
        val requestHash = "a".repeat(64)
        val expiresAt = Instant.now().plusSeconds(86_400)

        assertEquals(1, repository.insertIfAbsent(id, userId, key, requestHash, expiresAt))
        assertEquals(0, repository.insertIfAbsent(id, userId, key, requestHash, expiresAt))

        assertEquals(1, repository.complete(id, "{\"status\":\"ORDER_FAILED\"}", UUID.randomUUID()))
        assertEquals(0, repository.complete(id, "{\"status\":\"ORDER_FAILED\"}", UUID.randomUUID()))

        val record = repository.findByUserIdAndIdempotencyKey(userId, key)
        assertNotNull(record)
        assertEquals(requestHash, record!!.requestHash)
        assertEquals("{\"status\":\"ORDER_FAILED\"}", record.responseJson)
    }

    @Test
    fun `simultaneous requests claim the same Idempotency-Key only once`() {
        val userId = UUID.randomUUID()
        val key = "order-request-concurrent"
        val id = UUID.nameUUIDFromBytes("$userId:$key".toByteArray())
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        val transactionTemplate = TransactionTemplate(transactionManager)

        try {
            val claims = (1..2).map {
                executor.submit<Int> {
                    barrier.await(10, TimeUnit.SECONDS)
                    transactionTemplate.execute {
                        repository.insertIfAbsent(id, userId, key, "b".repeat(64), Instant.now().plusSeconds(86_400))
                    } ?: error("Claim transaction returned null")
                }
            }.map { it.get(15, TimeUnit.SECONDS) }

            assertEquals(listOf(0, 1), claims.sorted())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `held advisory lock rejects a concurrent duplicate without waiting`() {
        val userId = UUID.randomUUID()
        val key = "order-request-lock"
        val lockKey = "$userId:$key"
        val lockHeld = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val transactionTemplate = TransactionTemplate(transactionManager)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val holder = executor.submit {
                transactionTemplate.execute {
                    assertTrue(repository.tryAcquireClaimLock(lockKey))
                    lockHeld.countDown()
                    assertTrue(releaseLock.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS))

            val acquiredByDuplicate = transactionTemplate.execute {
                repository.tryAcquireClaimLock(lockKey)
            } ?: error("Lock transaction returned null")
            assertFalse(acquiredByDuplicate)

            releaseLock.countDown()
            holder.get(10, TimeUnit.SECONDS)
        } finally {
            releaseLock.countDown()
            executor.shutdownNow()
        }
    }

    private companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun registerPostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
        }
    }
}

@SpringBootConfiguration
@EnableAutoConfiguration(exclude = [RedissonAutoConfigurationV2::class])
@EntityScan(basePackageClasses = [OrderRequestIdempotency::class])
@EnableJpaRepositories(basePackageClasses = [OrderRequestIdempotencyRepository::class])
class OrderIdempotencyRepositoryTestApplication
