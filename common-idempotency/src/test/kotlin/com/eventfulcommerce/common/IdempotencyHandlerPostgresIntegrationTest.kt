package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.ProcessedEventRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = [IdempotencyTestApplication::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotencyHandlerPostgresIntegrationTest {

    @Autowired
    private lateinit var idempotencyHandler: IdempotencyHandler

    @Autowired
    private lateinit var processedEventRepository: ProcessedEventRepository

    @Autowired
    private lateinit var effectRepository: IdempotencyTestEffectRepository

    @BeforeEach
    fun cleanUp() {
        effectRepository.deleteAll()
        processedEventRepository.deleteAll()
    }

    @Test
    fun `중복 이벤트는 PostgreSQL 충돌 후에도 예외 없이 action을 건너뛴다`() {
        val eventId = UUID.randomUUID()
        var actionCalls = 0

        val first = idempotencyHandler.executeIdempotent(eventId) {
            actionCalls += 1
            effectRepository.save(IdempotencyTestEffect(UUID.randomUUID(), "first"))
        }
        val duplicate = idempotencyHandler.executeIdempotent(eventId) {
            actionCalls += 1
            effectRepository.save(IdempotencyTestEffect(UUID.randomUUID(), "duplicate"))
        }

        assertTrue(first is IdempotencyResult.Success)
        assertSame(IdempotencyResult.AlreadyProcessed, duplicate)
        assertEquals(1, actionCalls)
        assertEquals(1, effectRepository.count())
        assertTrue(processedEventRepository.existsById(eventId))
    }

    @Test
    fun `action 실패 시 marker와 도메인 변경이 함께 롤백되어 재처리할 수 있다`() {
        val eventId = UUID.randomUUID()
        val effectId = UUID.randomUUID()

        assertThrows(IllegalStateException::class.java) {
            idempotencyHandler.executeIdempotent(eventId) {
                effectRepository.saveAndFlush(IdempotencyTestEffect(effectId, "rolled-back"))
                throw IllegalStateException("domain failure")
            }
        }

        assertFalse(processedEventRepository.existsById(eventId))
        assertFalse(effectRepository.existsById(effectId))

        val retried = idempotencyHandler.executeIdempotent(eventId) {
            effectRepository.save(IdempotencyTestEffect(effectId, "retried"))
        }

        assertTrue(retried is IdempotencyResult.Success)
        assertTrue(processedEventRepository.existsById(eventId))
        assertEquals("retried", effectRepository.findById(effectId).orElseThrow().description)
    }

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
@EnableAutoConfiguration
@ComponentScan(basePackages = ["com.eventfulcommerce.common"])
@EntityScan(basePackages = ["com.eventfulcommerce.common"])
@EnableJpaRepositories(basePackages = ["com.eventfulcommerce.common"])
class IdempotencyTestApplication

@Entity
@Table(name = "idempotency_test_effect")
class IdempotencyTestEffect(
    @Id
    val id: UUID,

    @Column(nullable = false)
    val description: String
)

interface IdempotencyTestEffectRepository : JpaRepository<IdempotencyTestEffect, UUID>
