package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.ProcessedEventRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class IdempotencyHandlerTest {
    private lateinit var processedEventRepository: ProcessedEventRepository
    private lateinit var idempotencyHandler: IdempotencyHandler

    @BeforeEach
    fun setUp() {
        processedEventRepository = mockk()
        idempotencyHandler = IdempotencyHandler(processedEventRepository)
    }

    @Test
    fun `최초 이벤트는 marker를 선점하고 action을 실행한다`() {
        val eventId = UUID.randomUUID()
        var actionCalls = 0
        every { processedEventRepository.insertIfAbsent(eventId) } returns 1

        val result = idempotencyHandler.executeIdempotent(eventId) {
            actionCalls += 1
            "processed"
        }

        assertTrue(result is IdempotencyResult.Success)
        assertEquals("processed", (result as IdempotencyResult.Success).value)
        assertEquals(1, actionCalls)
        verify(exactly = 1) { processedEventRepository.insertIfAbsent(eventId) }
    }

    @Test
    fun `중복 이벤트는 action을 실행하지 않는다`() {
        val eventId = UUID.randomUUID()
        var actionCalls = 0
        every { processedEventRepository.insertIfAbsent(eventId) } returns 0

        val result = idempotencyHandler.executeIdempotent(eventId) {
            actionCalls += 1
            "should-not-run"
        }

        assertSame(IdempotencyResult.AlreadyProcessed, result)
        assertEquals(0, actionCalls)
        verify(exactly = 1) { processedEventRepository.insertIfAbsent(eventId) }
    }

    @Test
    fun `action 예외는 호출자에게 전파한다`() {
        val eventId = UUID.randomUUID()
        val failure = IllegalStateException("consumer failure")
        every { processedEventRepository.insertIfAbsent(eventId) } returns 1

        val thrown = assertThrows(IllegalStateException::class.java) {
            idempotencyHandler.executeIdempotent(eventId) {
                throw failure
            }
        }

        assertSame(failure, thrown)
        verify(exactly = 1) { processedEventRepository.insertIfAbsent(eventId) }
    }

    @Test
    fun `예상하지 못한 insert row count는 action 실행 전에 실패한다`() {
        val eventId = UUID.randomUUID()
        var actionCalls = 0
        every { processedEventRepository.insertIfAbsent(eventId) } returns 2

        assertThrows(IllegalStateException::class.java) {
            idempotencyHandler.executeIdempotent(eventId) {
                actionCalls += 1
            }
        }

        assertEquals(0, actionCalls)
    }
}
