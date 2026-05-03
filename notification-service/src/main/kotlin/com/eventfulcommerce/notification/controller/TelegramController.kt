package com.eventfulcommerce.notification.controller

import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.notification.service.TelegramRegistrationService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/telegram")
class TelegramController(
    private val telegramRegistrationService: TelegramRegistrationService
) {

    @PostMapping("/register")
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
    fun getRegistrationStatus(): ResponseEntity<TelegramStatusResponse> {
        val userId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(TelegramStatusResponse(
            userId = userId,
            registered = telegramRegistrationService.isRegistered(userId)
        ))
    }
}

data class RegisterChatIdRequest(
    @field:NotNull(message = "chatId는 필수입니다")
    @field:Positive(message = "chatId는 양수여야 합니다")
    val chatId: Long
)

data class TelegramRegistrationResponse(val success: Boolean, val message: String, val userId: UUID, val chatId: Long)
data class TelegramInfoResponse(val registered: Boolean, val userId: UUID, val chatId: Long?, val registeredAt: java.time.Instant?)
data class TelegramUnregisterResponse(val success: Boolean, val message: String)
data class TelegramStatusResponse(val userId: UUID, val registered: Boolean)
