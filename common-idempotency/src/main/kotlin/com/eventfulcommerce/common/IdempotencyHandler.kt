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
        return try {
            // 멱등성 체크: 이미 처리된 이벤트인지 확인
            processedEventRepository.save(ProcessedEvent(eventId))
            
            // 처음 처리하는 이벤트이므로 비즈니스 로직 실행
            val result = action()
            logger.debug("이벤트 처리 완료: eventId={}", eventId)
            IdempotencyResult.Success(result)
            
        } catch (e: DataIntegrityViolationException) {
            // 중복 키 제약 위반 = 이미 처리된 이벤트
            logger.debug("이미 처리된 이벤트: eventId={}", eventId)
            IdempotencyResult.AlreadyProcessed
            
        } catch (e: Exception) {
            // 기타 예외는 상위로 전파
            logger.error("이벤트 처리 중 오류 발생: eventId={}", eventId, e)
            throw e
        }
    }
}

sealed class IdempotencyResult<out T> {
    data class Success<T>(val value: T) : IdempotencyResult<T>()
    object AlreadyProcessed : IdempotencyResult<Nothing>()
}
