package com.eventfulcommerce.product.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException::class)
    fun handleNotFound(e: ProductNotFoundException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("message" to (e.message ?: "Not found")))

    @ExceptionHandler(ProductOwnershipException::class)
    fun handleForbidden(e: ProductOwnershipException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("message" to (e.message ?: "Forbidden")))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("message" to (e.message ?: "Bad request")))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<Map<String, Any>> {
        val errors = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "Invalid") }
        return ResponseEntity.badRequest().body(mapOf("errors" to errors))
    }
}
