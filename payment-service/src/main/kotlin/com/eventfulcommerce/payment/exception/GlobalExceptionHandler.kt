package com.eventfulcommerce.payment.exception

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import org.springframework.web.servlet.NoHandlerFoundException
import java.time.Instant

private val logger = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(ex.statusCode).body(
            ErrorResponse(code = "REQUEST_REJECTED", message = ex.reason ?: "요청이 거부되었습니다")
        )

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataConflict(ex: DataIntegrityViolationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(code = "DATA_CONFLICT", message = "이미 처리된 결제 거래와 충돌합니다")
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        logger.warn { "잘못된 인자: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(code = "INVALID_ARGUMENT", message = ex.message ?: "잘못된 요청입니다")
        )
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException): ResponseEntity<ErrorResponse> {
        logger.warn { "잘못된 상태: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(code = "INVALID_STATE", message = ex.message ?: "잘못된 요청입니다")
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleBadRequest(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(
            ErrorResponse(code = "INVALID_REQUEST", message = "잘못된 요청 형식입니다")
        )

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun handleNoResourceFound(ex: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(code = "NOT_FOUND", message = "요청한 경로를 찾을 수 없습니다")
        )

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "예상치 못한 예외: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(code = "INTERNAL_SERVER_ERROR", message = "서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.")
        )
    }
}

data class ErrorResponse(
    val code: String,
    val message: String,
    val details: Any? = null,
    val timestamp: Instant = Instant.now()
)
