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
import org.springframework.dao.DataIntegrityViolationException
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
    fun `처음 처리하는 이벤트는 저장 후 action을 실행하고 Success를 반환한다`() {
        val eventId = UUID.randomUUID()
        var actionCalls = 0

        every { processedEventRepository.save(any<ProcessedEvent>()) } answers { firstArg() }

        val result = idempotencyHandler.executeIdempotent(eventId) {
            actionCalls += 1
            "processed"
        }

        assertTrue(result is IdempotencyResult.Success)
        assertEquals("processed", (result as IdempotencyResult.Success).value)
        assertEquals(1, actionCalls)
        verify(exactly = 1) {
            processedEventRepository.save(
                match<ProcessedEvent> { it.eventId == eventId }
            )
        }
    }

    @Test
    fun `이미 처리된 이벤트는 action을 실행하지 않고 AlreadyProcessed를 반환한다`() {
        val eventId = UUID.randomUUID()
        var actionCalls = 0

        every {
            processedEventRepository.save(any<ProcessedEvent>())
        } throws DataIntegrityViolationException("duplicate event")

        val result = idempotencyHandler.executeIdempotent(eventId) {
            actionCalls += 1
            "should-not-run"
        }

        assertSame(IdempotencyResult.AlreadyProcessed, result)
        assertEquals(0, actionCalls)
        verify(exactly = 1) {
            processedEventRepository.save(
                match<ProcessedEvent> { it.eventId == eventId }
            )
        }
    }

    @Test
    fun `action에서 발생한 예외는 삼키지 않고 호출자에게 전파한다`() {
        val eventId = UUID.randomUUID()
        val failure = IllegalStateException("consumer failure")

        every { processedEventRepository.save(any<ProcessedEvent>()) } answers { firstArg() }

        val thrown = assertThrows(IllegalStateException::class.java) {
            idempotencyHandler.executeIdempotent(eventId) {
                throw failure
            }
        }

        assertSame(failure, thrown)
        verify(exactly = 1) {
            processedEventRepository.save(
                match<ProcessedEvent> { it.eventId == eventId }
            )
        }
    }
}
