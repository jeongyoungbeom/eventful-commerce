package com.eventfulcommerce.order.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

class InventoryReservationServiceTest {
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var service: InventoryReservationService

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk(relaxed = true)
        service = InventoryReservationService(redisTemplate, terminalRetentionSeconds = 600)
    }

    @AfterEach
    fun tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
        TransactionSynchronizationManager.setActualTransactionActive(false)
    }

    @Test
    fun `same reservation id returns the same id without requiring another logical reservation`() {
        val productId = UUID.randomUUID().toString()
        val orderId = UUID.randomUUID()
        val reservationId = UUID.randomUUID()
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                *anyVararg()
            )
        } returnsMany listOf(1L, 2L)

        val first = service.reserve(productId, orderId, 2, 600, reservationId)
        val retried = service.reserve(productId, orderId, 2, 600, reservationId)

        assertEquals(reservationId, first)
        assertEquals(reservationId, retried)
        verify(exactly = 2) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                match<List<String>> { keys ->
                    keys.size == 4 && keys.all { it.contains("{product:$productId}") }
                },
                *anyVararg()
            )
        }
    }

    @Test
    fun `insufficient stock returns null`() {
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                *anyVararg()
            )
        } returns 0L

        val result = service.reserve(
            UUID.randomUUID().toString(),
            UUID.randomUUID(),
            2,
            600,
            UUID.randomUUID()
        )

        assertNull(result)
    }

    @Test
    fun `DB rollback releases a newly created reservation immediately`() {
        val productId = UUID.randomUUID().toString()
        val reservationId = UUID.randomUUID()
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                *anyVararg()
            )
        } returnsMany listOf(1L, 1L)
        TransactionSynchronizationManager.initSynchronization()
        TransactionSynchronizationManager.setActualTransactionActive(true)

        service.reserve(productId, UUID.randomUUID(), 3, 600, reservationId)
        TransactionSynchronizationManager.getSynchronizations().single()
            .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)

        verify(exactly = 2) {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                *anyVararg()
            )
        }
    }

    @Test
    fun `commit and release expose explicit action results`() {
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                *anyVararg()
            )
        } returnsMany listOf(1L, 2L, 0L, 3L, -1L)

        assertEquals(
            InventoryReservationActionResult.APPLIED,
            service.commit("product", UUID.randomUUID(), 1)
        )
        assertEquals(
            InventoryReservationActionResult.ALREADY_APPLIED,
            service.release("product", UUID.randomUUID(), 1)
        )
        assertEquals(
            InventoryReservationActionResult.NOT_FOUND,
            service.commit("product", UUID.randomUUID(), 1)
        )
        assertEquals(
            InventoryReservationActionResult.TERMINAL_CONFLICT,
            service.release("product", UUID.randomUUID(), 1)
        )
        assertEquals(
            InventoryReservationActionResult.CORRUPTED,
            service.commit("product", UUID.randomUUID(), 1)
        )
    }

    @Test
    fun `terminal reservation cannot be reserved again`() {
        every {
            redisTemplate.execute(
                any<RedisScript<Long>>(),
                any<List<String>>(),
                *anyVararg()
            )
        } returns 3L

        assertThrows<InventoryReservationTerminalStateException> {
            service.reserve("product", UUID.randomUUID(), 1, 600, UUID.randomUUID())
        }
    }
}
