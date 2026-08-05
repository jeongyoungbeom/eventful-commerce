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
