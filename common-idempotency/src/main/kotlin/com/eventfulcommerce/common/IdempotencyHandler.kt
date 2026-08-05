package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.ProcessedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = LoggerFactory.getLogger(IdempotencyHandler::class.java)

@Component
class IdempotencyHandler(
    private val processedEventRepository: ProcessedEventRepository
) {
    @Transactional(rollbackFor = [Exception::class])
    fun <T> executeIdempotent(eventId: UUID, action: () -> T): IdempotencyResult<T> {
        val insertedRows = processedEventRepository.insertIfAbsent(eventId)
        if (insertedRows == 0) {
            logger.debug("이미 처리된 이벤트: eventId={}", eventId)
            return IdempotencyResult.AlreadyProcessed
        }
        check(insertedRows == 1) {
            "processed_event insert returned an unexpected row count: eventId=$eventId, rows=$insertedRows"
        }

        // marker와 action은 같은 DB 트랜잭션이다. action 실패 시 marker도 함께 롤백된다.
        return try {
            val result = action()
            logger.debug("이벤트 처리 완료: eventId={}", eventId)
            IdempotencyResult.Success(result)
        } catch (e: Exception) {
            logger.error("이벤트 처리 중 오류 발생: eventId={}", eventId, e)
            throw e
        }
    }
}

sealed class IdempotencyResult<out T> {
    data class Success<T>(val value: T) : IdempotencyResult<T>()
    object AlreadyProcessed : IdempotencyResult<Nothing>()
}
