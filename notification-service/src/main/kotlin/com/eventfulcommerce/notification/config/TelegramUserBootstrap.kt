package com.eventfulcommerce.notification.config

import com.eventfulcommerce.notification.domain.entity.UserChatId
import com.eventfulcommerce.notification.repository.UserChatIdRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Configuration
class TelegramUserBootstrap(
    private val properties: DefaultTelegramUserProperties
) {

    @Bean
    fun registerDefaultTelegramUser(userChatIdRepository: UserChatIdRepository) = ApplicationRunner {
        if (properties.userId.isBlank() || properties.chatId.isBlank()) {
            logger.info { "기본 Telegram 수신자 미등록: TELEGRAM_DEFAULT_USER_ID 또는 TELEGRAM_DEFAULT_CHAT_ID가 비어 있습니다." }
            return@ApplicationRunner
        }

        val userId = runCatching { UUID.fromString(properties.userId) }
            .getOrElse {
                logger.warn { "기본 Telegram 수신자 userId 형식이 올바르지 않습니다: ${properties.userId}" }
                return@ApplicationRunner
            }

        val chatId = properties.chatId.toLongOrNull()
        if (chatId == null) {
            logger.warn { "기본 Telegram 수신자 chatId 형식이 올바르지 않습니다: ${properties.chatId}" }
            return@ApplicationRunner
        }

        val existing = userChatIdRepository.findByUserId(userId)
        if (existing == null) {
            userChatIdRepository.save(UserChatId(userId = userId, chatId = chatId))
            logger.info { "기본 Telegram 수신자 등록 완료: userId=$userId, chatId=$chatId" }
        } else if (existing.chatId != chatId) {
            existing.chatId = chatId
            userChatIdRepository.save(existing)
            logger.info { "기본 Telegram 수신자 chatId 갱신 완료: userId=$userId, chatId=$chatId" }
        } else {
            logger.info { "기본 Telegram 수신자 이미 등록됨: userId=$userId" }
        }
    }
}
