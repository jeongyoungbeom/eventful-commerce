package com.eventfulcommerce.notification.controller

import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.notification.service.TelegramRegistrationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/telegram")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Telegram Notifications", description = "Telegram 알림 수신 등록 및 상태 조회 API")
class TelegramController(
    private val telegramRegistrationService: TelegramRegistrationService
) {

    @PostMapping("/register")
    @Operation(summary = "Telegram chatId 등록", description = "로그인 사용자의 Telegram chatId를 등록합니다. 주문/결제/배송 이벤트 알림을 이 chatId로 전송합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "chatId 등록 성공"),
        ApiResponse(responseCode = "400", description = "chatId 누락 또는 음수"),
        ApiResponse(responseCode = "401", description = "인증 실패")
    )
    fun registerChatId(
        @Valid @RequestBody request: RegisterChatIdRequest
    ): ResponseEntity<TelegramRegistrationResponse> {
        val userId = SecurityContextUtil.getCurrentUserId()
        telegramRegistrationService.register(userId, request.chatId)

        return ResponseEntity.ok(TelegramRegistrationResponse(
            success = true,
            message = "Telegram 알림 수신 등록이 완료되었습니다",
            userId = userId,
            chatId = request.chatId
        ))
    }

    @GetMapping("/my-chat-id")
    @Operation(summary = "내 Telegram chatId 조회", description = "로그인 사용자의 Telegram 알림 등록 정보와 등록 시간을 조회합니다.")
    @ApiResponses(ApiResponse(responseCode = "200", description = "등록 정보 조회 성공"))
    fun getMyChatId(): ResponseEntity<TelegramInfoResponse> {
        val userId = SecurityContextUtil.getCurrentUserId()
        val userChatId = telegramRegistrationService.getChatId(userId)

        return if (userChatId != null) {
            ResponseEntity.ok(TelegramInfoResponse(
                registered = true,
                userId = userChatId.userId,
                chatId = userChatId.chatId,
                registeredAt = userChatId.createdAt
            ))
        } else {
            ResponseEntity.ok(TelegramInfoResponse(
                registered = false,
                userId = userId,
                chatId = null,
                registeredAt = null
            ))
        }
    }

    @DeleteMapping("/unregister")
    @Operation(summary = "Telegram 알림 해제", description = "로그인 사용자의 Telegram chatId 등록을 삭제합니다.")
    @ApiResponses(ApiResponse(responseCode = "200", description = "해제 처리 결과 반환"))
    fun unregisterChatId(): ResponseEntity<TelegramUnregisterResponse> {
        val userId = SecurityContextUtil.getCurrentUserId()
        val success = telegramRegistrationService.unregister(userId)

        return if (success) {
            ResponseEntity.ok(TelegramUnregisterResponse(success = true, message = "Telegram 알림 수신이 해제되었습니다"))
        } else {
            ResponseEntity.ok(TelegramUnregisterResponse(success = false, message = "등록된 Telegram 정보가 없습니다"))
        }
    }

    @GetMapping("/status")
    @Operation(summary = "Telegram 등록 상태 조회", description = "로그인 사용자의 Telegram 알림 등록 여부만 간단히 조회합니다.")
    @ApiResponses(ApiResponse(responseCode = "200", description = "등록 상태 조회 성공"))
    fun getRegistrationStatus(): ResponseEntity<TelegramStatusResponse> {
        val userId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(TelegramStatusResponse(
            userId = userId,
            registered = telegramRegistrationService.isRegistered(userId)
        ))
    }
}

@Schema(description = "Telegram chatId 등록 요청")
data class RegisterChatIdRequest(
    @field:Schema(description = "Telegram Bot이 전달받은 사용자 chatId", example = "123456789", required = true)
    @field:NotNull(message = "chatId는 필수입니다")
    @field:Positive(message = "chatId는 양수여야 합니다")
    val chatId: Long
)

@Schema(description = "Telegram 등록 응답")
data class TelegramRegistrationResponse(val success: Boolean, val message: String, val userId: UUID, val chatId: Long)

@Schema(description = "Telegram 등록 정보 응답")
data class TelegramInfoResponse(val registered: Boolean, val userId: UUID, val chatId: Long?, val registeredAt: java.time.Instant?)

@Schema(description = "Telegram 해제 응답")
data class TelegramUnregisterResponse(val success: Boolean, val message: String)

@Schema(description = "Telegram 등록 상태 응답")
data class TelegramStatusResponse(val userId: UUID, val registered: Boolean)
