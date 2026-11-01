package com.eventfulcommerce.inventory.controller

import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class InventoryExceptionHandler {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException::class)
    fun status(ex: org.springframework.web.server.ResponseStatusException) =
        ResponseEntity.status(ex.statusCode).body(mapOf("message" to (ex.reason ?: "요청을 처리할 수 없습니다")))

    @ExceptionHandler(IllegalArgumentException::class, ArithmeticException::class, MethodArgumentNotValidException::class,
        ConstraintViolationException::class, HttpMessageNotReadableException::class)
    fun invalid(ex: Exception) = ResponseEntity.badRequest().body(mapOf("message" to "입력 형식·수량·금액·사용기한을 확인해 주세요"))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun conflict(ex: Exception) = ResponseEntity.status(409).body(mapOf("message" to "중복 식별자 또는 재고 제약 충돌입니다"))

    @ExceptionHandler(AccessDeniedException::class)
    fun denied(ex: Exception) = ResponseEntity.status(403).body(mapOf("message" to "요청 권한이 없습니다"))
}
