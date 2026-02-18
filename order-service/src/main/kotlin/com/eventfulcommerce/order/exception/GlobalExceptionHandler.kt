package com.eventfulcommerce.order.exception

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 전역 예외 처리 핸들러
 * 모든 REST API 예외를 일관된 형식으로 응답합니다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    /**
     * 재고 부족 예외 처리
     */
    @ExceptionHandler(InsufficientInventoryException::class)
    fun handleInsufficientInventory(ex: InsufficientInventoryException): ResponseEntity<ErrorResponse> {
        logger.warn { "재고 부족 예외 발생: ${ex.message}" }
        
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(
                code = "INSUFFICIENT_INVENTORY",
                message = ex.message ?: "재고가 부족합니다.",
                details = ex.failedOrderIds,
                timestamp = Instant.now()
            ))
    }

    /**
     * 잘못된 주문 상태 예외 처리
     */
    @ExceptionHandler(InvalidOrderStatusException::class)
    fun handleInvalidOrderStatus(ex: InvalidOrderStatusException): ResponseEntity<ErrorResponse> {
        logger.warn { "잘못된 주문 상태 예외: ${ex.message}" }
        
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(
                code = "INVALID_ORDER_STATUS",
                message = ex.message ?: "잘못된 주문 상태입니다.",
                details = mapOf<String, String>(
                    "orderId" to ex.orderId.toString(),
                    "currentStatus" to ex.currentStatus.toString(),
                    "expectedStatus" to ex.expectedStatus.toString()
                ),
                timestamp = Instant.now()
            ))
    }

    /**
     * 주문을 찾을 수 없는 예외 처리
     */
    @ExceptionHandler(OrderNotFoundException::class)
    fun handleOrderNotFound(ex: OrderNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn { "주문 미존재 예외: ${ex.message}" }
        
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(
                code = "ORDER_NOT_FOUND",
                message = ex.message ?: "주문을 찾을 수 없습니다.",
                details = mapOf<String, String>("orderId" to ex.orderId.toString()),
                timestamp = Instant.now()
            ))
    }

    /**
     * IllegalStateException 처리
     */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "IllegalStateException 발생: ${ex.message}" }
        
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(
                code = "ILLEGAL_STATE",
                message = ex.message ?: "시스템 오류가 발생했습니다.",
                timestamp = Instant.now()
            ))
    }

    /**
     * IllegalArgumentException 처리
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        logger.warn { "잘못된 인자 예외: ${ex.message}" }
        
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(
                code = "INVALID_ARGUMENT",
                message = ex.message ?: "잘못된 요청입니다.",
                timestamp = Instant.now()
            ))
    }

    /**
     * 모든 예외를 처리하는 폴백 핸들러
     */
    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "예상치 못한 예외 발생: ${ex.message}" }
        
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(
                code = "INTERNAL_SERVER_ERROR",
                message = "서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
                timestamp = Instant.now()
            ))
    }
}

/**
 * 에러 응답 DTO
 */
data class ErrorResponse(
    /**
     * 에러 코드 (예: INSUFFICIENT_INVENTORY)
     */
    val code: String,
    
    /**
     * 사용자에게 보여줄 메시지
     */
    val message: String,
    
    /**
     * 추가 상세 정보 (선택)
     */
    val details: Any? = null,
    
    /**
     * 에러 발생 시각
     */
    val timestamp: Instant
)
