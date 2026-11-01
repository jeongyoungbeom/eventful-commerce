package com.eventfulcommerce.order.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class InventoryReservationRedisIntegrationTest {
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var service: InventoryReservationService

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(
            RedisStandaloneConfiguration(redis.host, redis.getMappedPort(REDIS_PORT))
        ).apply { afterPropertiesSet() }
        redisTemplate = StringRedisTemplate(connectionFactory).apply { afterPropertiesSet() }
        connectionFactory.connection.serverCommands().flushAll()
        service = InventoryReservationService(redisTemplate, terminalRetentionSeconds = 3_600)
    }

    @AfterEach
    fun tearDown() {
        connectionFactory.destroy()
    }

    @Test
    fun `reserve and commit remain idempotent after terminal transition`() {
        val productId = UUID.randomUUID().toString()
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        service.initializeStock(productId, 10)

        assertEquals(reservationId, service.reserve(productId, orderId, 2, 600, reservationId))
        assertEquals(reservationId, service.reserve(productId, orderId, 2, 600, reservationId))
        assertEquals(8L, service.getAvailableStock(productId))

        assertEquals(
            InventoryReservationActionResult.APPLIED,
            service.commit(productId, reservationId, 2)
        )
        assertEquals(
            InventoryReservationActionResult.ALREADY_APPLIED,
            service.commit(productId, reservationId, 2)
        )
        assertThrows<InventoryReservationTerminalStateException> {
            service.reserve(productId, orderId, 2, 600, reservationId)
        }

        assertEquals(8L, service.getAvailableStock(productId))
        assertEquals(
            "COMMITTED",
            redisTemplate.opsForHash<String, String>().get(holdKey(productId, reservationId), "status")
        )
        assertTrue(redisTemplate.getExpire(holdKey(productId, reservationId)) > 0)
    }

    @Test
    fun `release restores stock once and keeps a released tombstone`() {
        val productId = UUID.randomUUID().toString()
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        service.initializeStock(productId, 10)
        service.reserve(productId, orderId, 3, 600, reservationId)

        assertEquals(
            InventoryReservationActionResult.APPLIED,
            service.release(productId, reservationId, 3)
        )
        assertEquals(
            InventoryReservationActionResult.ALREADY_APPLIED,
            service.release(productId, reservationId, 3)
        )
        assertEquals(10L, service.getAvailableStock(productId))
        assertThrows<InventoryReservationTerminalStateException> {
            service.reserve(productId, orderId, 3, 600, reservationId)
        }
        assertEquals(
            "RELEASED",
            redisTemplate.opsForHash<String, String>().get(holdKey(productId, reservationId), "status")
        )
    }

    @Test
    fun `corrupted hold count fails before stock is changed`() {
        val productId = UUID.randomUUID().toString()
        val reservationId = UUID.randomUUID()
        service.initializeStock(productId, 10)
        redisTemplate.opsForValue().set(holdCountKey(productId), "not-a-number")

        assertThrows<IllegalStateException> {
            service.reserve(productId, UUID.randomUUID(), 2, 600, reservationId)
        }

        assertEquals(10L, service.getAvailableStock(productId))
        assertFalse(redisTemplate.hasKey(holdKey(productId, reservationId)))
    }

    @Test
    fun `confirmed reservation is restocked once`() {
        val productId = UUID.randomUUID().toString()
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        service.initializeStock(productId, 10)
        service.reserve(productId, orderId, 2, 600, reservationId)
        service.commit(productId, reservationId, 2)

        assertEquals(
            InventoryReservationActionResult.APPLIED,
            service.restock(productId, reservationId, 2)
        )
        assertEquals(
            InventoryReservationActionResult.ALREADY_APPLIED,
            service.restock(productId, reservationId, 2)
        )
        assertEquals(10L, service.getAvailableStock(productId))
        assertEquals(
            InventoryReservationActionResult.TERMINAL_CONFLICT,
            service.release(productId, reservationId, 2)
        )
        assertEquals(
            "RESTOCKED",
            redisTemplate.opsForHash<String, String>().get(holdKey(productId, reservationId), "status")
        )
        assertThrows<InventoryReservationTerminalStateException> {
            service.reserve(productId, orderId, 2, 600, reservationId)
        }
    }

    @Test
    fun `confirmed order from legacy version creates an idempotent restock tombstone`() {
        val productId = UUID.randomUUID().toString()
        val reservationId = UUID.randomUUID()
        service.initializeStock(productId, 8)

        assertEquals(
            InventoryReservationActionResult.APPLIED,
            service.restock(productId, reservationId, 2)
        )
        assertEquals(
            InventoryReservationActionResult.ALREADY_APPLIED,
            service.restock(productId, reservationId, 2)
        )
        assertEquals(10L, service.getAvailableStock(productId))
    }

    @Test
    fun `stock update event changes Redis inventory only once`() {
        val productId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID()
        service.initializeStock(productId, 10)

        assertEquals(
            InventoryReservationActionResult.APPLIED,
            service.adjustStockIdempotent(productId, eventId, 5)
        )
        assertEquals(
            InventoryReservationActionResult.ALREADY_APPLIED,
            service.adjustStockIdempotent(productId, eventId, 5)
        )
        assertEquals(15L, service.getAvailableStock(productId))
    }

    @Test
    fun `혼합 확정은 실제 기한 경과를 거부하고 기존 입점 확정 경로는 유지한다`() {
        val productId = UUID.randomUUID().toString(); val reservationId = UUID.randomUUID()
        service.initializeStock(productId, 10)
        service.reserve(productId, UUID.randomUUID(), 2, 600, reservationId, java.time.Instant.now().minusSeconds(1))
        assertEquals(InventoryReservationActionResult.TERMINAL_CONFLICT, service.commit(productId, reservationId, 2, true))
        assertEquals(InventoryStockSnapshot(8, 2), service.getStockSnapshot(productId))
        assertEquals(InventoryReservationState.RESERVED, service.getReservationMetadata(productId, reservationId)!!.state)
        assertEquals(InventoryReservationActionResult.APPLIED, service.commit(productId, reservationId, 2))
        assertEquals(InventoryStockSnapshot(8, 0), service.getStockSnapshot(productId))
    }

    @Test
    fun `혼합 확정은 기한 경과 후 재시도도 기확정 원본을 한 번만 적용한다`() {
        val productId = UUID.randomUUID().toString(); val reservationId = UUID.randomUUID()
        service.initializeStock(productId, 10)
        service.reserve(productId, UUID.randomUUID(), 2, 600, reservationId)
        assertEquals(InventoryReservationActionResult.APPLIED, service.commit(productId, reservationId, 2, true))
        redisTemplate.opsForHash<String, String>().put(holdKey(productId, reservationId), "expiresAtEpochMilli", "1")
        assertEquals(InventoryReservationActionResult.ALREADY_APPLIED, service.commit(productId, reservationId, 2, true))
        assertEquals(InventoryStockSnapshot(8, 0), service.getStockSnapshot(productId))
        assertTrue(service.findExpiredReservationIds(productId, java.time.Instant.now().plusSeconds(601), 10).isEmpty())
    }

    @Test
    fun `혼합 확정의 만료 메타데이터 손상은 재고와 예약을 변경하지 않는다`() {
        val productId = UUID.randomUUID().toString(); val reservationId = UUID.randomUUID()
        service.initializeStock(productId, 10)
        service.reserve(productId, UUID.randomUUID(), 2, 600, reservationId)
        for (value in listOf("invalid", "0")) {
            redisTemplate.opsForHash<String, String>().put(holdKey(productId, reservationId), "expiresAtEpochMilli", value)
            assertEquals(InventoryReservationActionResult.CORRUPTED, service.commit(productId, reservationId, 2, true))
            assertEquals(InventoryStockSnapshot(8, 2), service.getStockSnapshot(productId))
            assertEquals("RESERVED", redisTemplate.opsForHash<String, String>().get(holdKey(productId, reservationId), "status"))
        }
    }

    private fun holdKey(productId: String, reservationId: UUID) =
        "{product:$productId}:hold:$reservationId"

    private fun holdCountKey(productId: String) = "{product:$productId}:holdCount"

    private class RedisContainer(imageName: DockerImageName) : GenericContainer<RedisContainer>(imageName)

    private companion object {
        const val REDIS_PORT = 6379

        @Container
        @JvmStatic
        val redis = RedisContainer(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(REDIS_PORT)
    }
}
