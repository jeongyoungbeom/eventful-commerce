package com.eventfulcommerce.notification.service

import com.eventfulcommerce.notification.domain.entity.UserChatId
import com.eventfulcommerce.notification.repository.UserChatIdRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class TelegramRegistrationService(
    private val userChatIdRepository: UserChatIdRepository,
    private val telegramService: TelegramService
) {
    
    /**
     * Telegram chatId 등록 또는 업데이트
     */
    @Transactional
    fun register(userId: UUID, chatId: Long) {
        val existing = userChatIdRepository.findByUserId(userId)
        
        if (existing != null) {
            // 이미 등록된 경우 - chatId 업데이트
            if (existing.chatId != chatId) {
                existing.chatId = chatId
                userChatIdRepository.save(existing)
                logger.info { "📝 Telegram chatId 업데이트: userId=$userId, chatId=$chatId" }
                
                // 확인 메시지 전송
                telegramService.sendMessage(
                    chatId,
                    "✅ Telegram 알림 수신 설정이 업데이트되었습니다!"
                )
            } else {
                logger.info { "ℹ️ 이미 등록된 chatId: userId=$userId, chatId=$chatId" }
            }
        } else {
            // 신규 등록
            val userChatId = UserChatId(
                userId = userId,
                chatId = chatId
            )
            userChatIdRepository.save(userChatId)
            logger.info { "✅ Telegram chatId 등록 완료: userId=$userId, chatId=$chatId" }
            
            // 환영 메시지 전송
            telegramService.sendMessage(
                chatId,
                """
                ✅ <b>Telegram 알림 수신 등록 완료!</b>
                
                이제 주문, 배송, 결제 관련 알림을 받을 수 있습니다.
                
                📱 알림 종류:
                • 주문 예약 완료
                • 결제 완료
                • 배송 시작
                • 배송 완료
                """.trimIndent()
            )
        }
    }
    
    /**
     * Telegram chatId 조회
     */
    fun getChatId(userId: UUID): UserChatId? {
        return userChatIdRepository.findByUserId(userId)
    }
    
    /**
     * Telegram chatId 해제 (알림 수신 중단)
     */
    @Transactional
    fun unregister(userId: UUID): Boolean {
        val existing = userChatIdRepository.findByUserId(userId)
            ?: return false
        
        val chatId = existing.chatId
        userChatIdRepository.delete(existing)
        
        logger.info { "🗑️ Telegram chatId 해제: userId=$userId, chatId=$chatId" }
        
        // 해제 확인 메시지 전송
        telegramService.sendMessage(
            chatId,
            "👋 Telegram 알림 수신이 해제되었습니다.\n다시 등록하려면 앱에서 설정해주세요."
        )
        
        return true
    }
    
    /**
     * Telegram 등록 여부 확인
     */
    fun isRegistered(userId: UUID): Boolean {
        return userChatIdRepository.findByUserId(userId) != null
    }
}
