package com.eventfulcommerce.common

import com.eventfulcommerce.common.repository.ProcessedEventRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.util.UUID

private val logger = LoggerFactory.getLogger(IdempotencyHandler::class.java)

@Component
class IdempotencyHandler(
    private val processedEventRepository: ProcessedEventRepository
) {
    fun <T> executeIdempotent(eventId: UUID, action: () -> T): IdempotencyResult<T> {
        // save()의 DataIntegrityViolationException만 "중복 이벤트"로 판단
        try {
            processedEventRepository.save(ProcessedEvent(eventId))
        } catch (e: DataIntegrityViolationException) {
            logger.debug("이미 처리된 이벤트: eventId={}", eventId)
            return IdempotencyResult.AlreadyProcessed
        }

        // action()의 예외는 그대로 전파 — 호출자(Kafka consumer)가 처리
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
