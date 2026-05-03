package com.eventfulcommerce.user.controller

import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.user.dto.UserExistsResponse
import com.eventfulcommerce.user.dto.UserResponse
import com.eventfulcommerce.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/users")
class UserController(
    private val userService: UserService
) {

    @GetMapping("/{userId}")
    fun getUser(@PathVariable userId: UUID): ResponseEntity<UserResponse> {
        val currentUserId = SecurityContextUtil.getCurrentUserId()
        val currentRole = SecurityContextUtil.getCurrentUserRole()

        // 본인이거나 ADMIN만 조회 가능
        if (currentUserId != userId && currentRole != "ADMIN") {
            throw AccessDeniedException("본인의 정보만 조회할 수 있습니다")
        }

        val user = userService.findById(userId)
        return ResponseEntity.ok(UserResponse(
            userId = user.id,
            email = user.email,
            name = user.name,
            createdAt = user.createdAt
        ))
    }

    @GetMapping("/{userId}/exists")
    fun userExists(@PathVariable userId: UUID): ResponseEntity<UserExistsResponse> {
        val exists = userService.existsById(userId)
        return ResponseEntity.ok(UserExistsResponse(exists))
    }
}
