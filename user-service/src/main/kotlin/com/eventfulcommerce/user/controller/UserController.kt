package com.eventfulcommerce.user.controller

import com.eventfulcommerce.user.domain.entity.User
import com.eventfulcommerce.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService
) {
    
    /**
     * User 정보 조회
     */
    @GetMapping("/{userId}")
    fun getUser(@PathVariable userId: UUID): ResponseEntity<UserResponse> {
        val user = userService.findById(userId)
        
        val response = UserResponse(
            userId = user.id,
            email = user.email,
            name = user.name,
            role = user.role,
            emailVerified = user.emailVerified,
            createdAt = user.createdAt
        )
        
        return ResponseEntity.ok(response)
    }
    
    /**
     * User 존재 여부 확인
     */
    @GetMapping("/{userId}/exists")
    fun userExists(@PathVariable userId: UUID): ResponseEntity<UserExistsResponse> {
        val exists = userService.existsById(userId)
        return ResponseEntity.ok(UserExistsResponse(exists))
    }
}

/**
 * User 응답 DTO
 */
data class UserResponse(
    val userId: UUID,
    val email: String,
    val name: String,
    val role: com.eventfulcommerce.user.domain.entity.UserRole,
    val emailVerified: Boolean,
    val createdAt: java.time.Instant
)

/**
 * User 존재 여부 응답
 */
data class UserExistsResponse(
    val exists: Boolean
)
