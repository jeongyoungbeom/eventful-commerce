package com.eventfulcommerce.user.exception

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

private val logger = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler {
    
    /**
     * Validation 에러 처리
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(
        ex: MethodArgumentNotValidException
    ): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.allErrors.map { error ->
            val fieldName = (error as FieldError).field
            val message = error.defaultMessage ?: "유효하지 않은 값입니다"
            "$fieldName: $message"
        }
        
        logger.warn { "Validation 에러: $errors" }
        
        return ResponseEntity
            .badRequest()
            .body(ErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                error = "Validation Failed",
                message = errors.joinToString(", "),
                timestamp = Instant.now()
            ))
    }
    
    /**
     * 중복 이메일 에러
     */
    @ExceptionHandler(DuplicateEmailException::class)
    fun handleDuplicateEmailException(
        ex: DuplicateEmailException
    ): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                error = "Duplicate Email",
                message = ex.message ?: "중복된 이메일입니다",
                timestamp = Instant.now()
            ))
    }
    
    /**
     * 중복 사업자 등록번호 에러
     */
    @ExceptionHandler(DuplicateBusinessNumberException::class)
    fun handleDuplicateBusinessNumberException(
        ex: DuplicateBusinessNumberException
    ): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                error = "Duplicate Business Number",
                message = ex.message ?: "중복된 사업자 등록번호입니다",
                timestamp = Instant.now()
            ))
    }
    
    /**
     * 사용자를 찾을 수 없음
     */
    @ExceptionHandler(UserNotFoundException::class)
    fun handleUserNotFoundException(
        ex: UserNotFoundException
    ): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(
                status = HttpStatus.NOT_FOUND.value(),
                error = "User Not Found",
                message = ex.message ?: "사용자를 찾을 수 없습니다",
                timestamp = Instant.now()
            ))
    }
    
    /**
     * 잘못된 인증 정보
     */
    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentialsException(
        ex: InvalidCredentialsException
    ): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(
                status = HttpStatus.UNAUTHORIZED.value(),
                error = "Invalid Credentials",
                message = ex.message ?: "인증 정보가 일치하지 않습니다",
                timestamp = Instant.now()
            ))
    }
    
    /**
     * 계정 잠금
     */
    @ExceptionHandler(AccountLockedException::class)
    fun handleAccountLockedException(
        ex: AccountLockedException
    ): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse(
                status = HttpStatus.FORBIDDEN.value(),
                error = "Account Locked",
                message = ex.message ?: "계정이 잠겼습니다",
                timestamp = Instant.now()
            ))
    }
    
    /**
     * 유효하지 않은 토큰
     */
    @ExceptionHandler(InvalidTokenException::class, ExpiredTokenException::class)
    fun handleTokenException(
        ex: CustomException
    ): ResponseEntity<ErrorResponse> {
        logger.warn { ex.message }
        
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(
                status = HttpStatus.UNAUTHORIZED.value(),
                error = "Invalid Token",
                message = ex.message ?: "유효하지 않은 토큰입니다",
                timestamp = Instant.now()
            ))
    }
    
    /**
     * 모든 예외 처리 (Fallback)
     */
    @ExceptionHandler(Exception::class)
    fun handleAllExceptions(
        ex: Exception
    ): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "예상치 못한 오류 발생" }
        
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(
                status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                error = "Internal Server Error",
                message = "서버 오류가 발생했습니다",
                timestamp = Instant.now()
            ))
    }
}

/**
 * 에러 응답 DTO
 */
data class ErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val timestamp: Instant
)
