package com.eventfulcommerce.notification.service

import com.eventfulcommerce.notification.config.TelegramProperties
import com.eventfulcommerce.notification.repository.UserChatIdRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class TelegramService(
    private val telegramProperties: TelegramProperties,
    private val userChatIdRepository: UserChatIdRepository,
    private val objectMapper: ObjectMapper,
    restTemplateBuilder: RestTemplateBuilder
) {
    private val restTemplate: RestTemplate = restTemplateBuilder.build()
    private val baseUrl = "https://api.telegram.org/bot${telegramProperties.token}"

    /**
     * 텔레그램 메시지 전송
     */
    fun sendMessage(chatId: Long, text: String): String? {
        val url = "$baseUrl/sendMessage"
        
        val payload = mapOf(
            "chat_id" to chatId,
            "text" to text,
            "parse_mode" to "HTML"
        )
        
        return try {
            val response = restTemplate.postForObject(url, payload, Map::class.java)
            logger.info { "📤 텔레그램 메시지 전송 성공: chatId=$chatId" }
            
            // 메시지 ID 추출
            @Suppress("UNCHECKED_CAST")
            val result = response?.get("result") as? Map<String, Any>
            result?.get("message_id")?.toString()
        } catch (e: Exception) {
            logger.error(e) { "❌ 텔레그램 메시지 전송 실패: chatId=$chatId" }
            null
        }
    }

    /**
     * userId로 메시지 전송
     */
    fun sendNotification(userId: UUID, text: String): String? {
        val userChatId = userChatIdRepository.findByUserId(userId)
        
        if (userChatId == null) {
            logger.warn { "⚠️ userId에 해당하는 chatId를 찾을 수 없음: userId=$userId" }
            return null
        }
        
        return sendMessage(userChatId.chatId, text)
    }
}
