package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.OutboxEventRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = [OutboxRepositoryTestApplication::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxEventRepositoryPostgresIntegrationTest {

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var outboxEventService: OutboxEventService

    @BeforeEach
    fun cleanUp() {
        outboxEventRepository.deleteAll()
    }

    @Test
    fun `동시에 claim을 시도해도 PostgreSQL에서는 하나만 PROCESSING으로 전이한다`() {
        val event = outboxEventRepository.saveAndFlush(outboxEvent())
        val now = Instant.now()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Boolean> {
                start.await(5, TimeUnit.SECONDS)
                outboxEventService.claim(event.id, UUID.randomUUID(), now)
            }
            val second = executor.submit<Boolean> {
                start.await(5, TimeUnit.SECONDS)
                outboxEventService.claim(event.id, UUID.randomUUID(), now)
            }

            start.countDown()
            assertEquals(1, listOf(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)).count { it })
        } finally {
            executor.shutdownNow()
        }

        val claimed = outboxEventRepository.findById(event.id).orElseThrow()
        assertEquals(OutboxStatus.PROCESSING, claimed.status)
        assertTrue(claimed.processingToken != null)
    }

    @Test
    fun `오래된 callback token은 새 claim의 SENT 전이를 수행할 수 없다`() {
        val event = outboxEventRepository.saveAndFlush(outboxEvent())
        val now = Instant.now()
        val claimToken = UUID.randomUUID()

        assertTrue(outboxEventService.claim(event.id, claimToken, now))
        assertFalse(outboxEventService.markAsSent(event.id, UUID.randomUUID()))
        assertTrue(outboxEventService.markAsSent(event.id, claimToken))

        val sent = outboxEventRepository.findById(event.id).orElseThrow()
        assertEquals(OutboxStatus.SENT, sent.status)
        assertTrue(sent.processingToken == null)
    }

    private fun outboxEvent() = OutboxEvent(
        aggregateType = "ORDER",
        aggregateId = UUID.randomUUID(),
        eventType = "ORDER_CREATED",
        payload = "{}"
    )

    companion object {
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
@EnableAutoConfiguration(exclude = [KafkaAutoConfiguration::class])
@ComponentScan(
    basePackages = ["com.eventfulcommerce.common"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [OutboxPublisher::class]),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["com\\.eventfulcommerce\\.common\\.metrics\\..*"])
    ]
)
@EntityScan(basePackages = ["com.eventfulcommerce.common"])
@EnableJpaRepositories(basePackages = ["com.eventfulcommerce.common"])
class OutboxRepositoryTestApplication
