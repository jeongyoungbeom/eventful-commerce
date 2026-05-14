package com.eventfulcommerce.product.exception

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant

private val logger = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException::class)
    fun handleNotFound(ex: ProductNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn { "상품 미존재: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(code = "PRODUCT_NOT_FOUND", message = ex.message ?: "상품을 찾을 수 없습니다")
        )
    }

    @ExceptionHandler(ProductOwnershipException::class)
    fun handleForbidden(ex: ProductOwnershipException): ResponseEntity<ErrorResponse> {
        logger.warn { "상품 권한 없음: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(code = "PRODUCT_FORBIDDEN", message = ex.message ?: "해당 상품에 대한 권한이 없습니다")
        )
    }

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

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "Invalid") }
        return ResponseEntity.badRequest().body(
            ErrorResponse(code = "VALIDATION_FAILED", message = "요청 값이 올바르지 않습니다", details = errors)
        )
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ErrorResponse> {
        val errors = ex.constraintViolations.associate { it.propertyPath.toString() to it.message }
        return ResponseEntity.badRequest().body(
            ErrorResponse(code = "VALIDATION_FAILED", message = "요청 값이 올바르지 않습니다", details = errors)
        )
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(ex: NoResourceFoundException): ResponseEntity<ErrorResponse> =
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
