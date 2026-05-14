package com.eventfulcommerce.user.controller

import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.user.dto.UserResponse
import com.eventfulcommerce.user.service.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/users")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Users", description = "사용자 프로필 조회 API")
class UserController(
    private val userService: UserService
) {

    @GetMapping("/{userId}")
    @Operation(summary = "사용자 단건 조회", description = "본인 또는 ADMIN 권한으로 특정 사용자 프로필을 조회합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "사용자 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 실패"),
        ApiResponse(responseCode = "403", description = "본인 또는 ADMIN이 아님"),
        ApiResponse(responseCode = "404", description = "사용자 없음")
    )
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

}
