package com.eventfulcommerce.user.exception

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant

private val logger = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.allErrors.associate { error ->
            (error as FieldError).field to (error.defaultMessage ?: "유효하지 않은 값입니다")
        }
        logger.warn { "Validation 에러: $errors" }
        return ResponseEntity.badRequest().body(
            ErrorResponse(code = "VALIDATION_FAILED", message = "요청 값이 올바르지 않습니다", details = errors)
        )
    }

    @ExceptionHandler(DuplicateEmailException::class)
    fun handleDuplicateEmail(ex: DuplicateEmailException): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(code = "DUPLICATE_EMAIL", message = ex.message ?: "중복된 이메일입니다")
        )
    }

    @ExceptionHandler(DuplicateBusinessNumberException::class)
    fun handleDuplicateBusinessNumber(ex: DuplicateBusinessNumberException): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(code = "DUPLICATE_BUSINESS_NUMBER", message = ex.message ?: "중복된 사업자 등록번호입니다")
        )
    }

    @ExceptionHandler(UserNotFoundException::class)
    fun handleUserNotFound(ex: UserNotFoundException): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(code = "USER_NOT_FOUND", message = ex.message ?: "사용자를 찾을 수 없습니다")
        )
    }

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ErrorResponse(code = "INVALID_CREDENTIALS", message = ex.message ?: "이메일 또는 비밀번호가 올바르지 않습니다")
        )
    }

    @ExceptionHandler(AccountLockedException::class)
    fun handleAccountLocked(ex: AccountLockedException): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(code = "ACCOUNT_LOCKED", message = ex.message ?: "계정이 잠겼습니다")
        )
    }

    @ExceptionHandler(InvalidTokenException::class, ExpiredTokenException::class)
    fun handleTokenException(ex: CustomException): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ErrorResponse(code = "INVALID_TOKEN", message = ex.message ?: "유효하지 않은 토큰입니다")
        )
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResourceFound(ex: NoResourceFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(code = "NOT_FOUND", message = "요청한 경로를 찾을 수 없습니다")
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "예상치 못한 오류: ${ex.message}" }
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
