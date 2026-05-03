package com.eventfulcommerce.notification.config

import com.eventfulcommerce.notification.service.TelegramRegistrationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * 개발/테스트 환경을 위한 기본 Telegram 사용자 등록
 *
 * 프로덕션 환경에서는 환경변수를 설정하지 않으면 자동 등록이 skip됩니다.
 * 실제 사용자는 TelegramController API를 통해 등록해야 합니다.
 */
@Configuration
class TelegramUserBootstrap(
    private val properties: DefaultTelegramUserProperties
) {

    @Bean
    fun registerDefaultTelegramUser(
        telegramRegistrationService: TelegramRegistrationService
    ) = ApplicationRunner {
        // 환경변수가 비어있으면 skip (프로덕션 권장)
        if (properties.userId.isBlank() || properties.chatId.isBlank()) {
            logger.info {
                "⏭️  기본 Telegram 사용자 자동 등록 건너뜀 " +
                "(TELEGRAM_DEFAULT_USER_ID 또는 TELEGRAM_DEFAULT_CHAT_ID 미설정)"
            }
            logger.info {
                "💡 사용자는 POST /telegram/register API를 통해 등록할 수 있습니다"
            }
            return@ApplicationRunner
        }

        // userId, chatId 검증
        val userId = runCatching { UUID.fromString(properties.userId) }
            .getOrElse {
                logger.warn {
                    "⚠️  기본 Telegram 사용자 userId 형식 오류: ${properties.userId}"
                }
                return@ApplicationRunner
            }

        val chatId = properties.chatId.toLongOrNull()
        if (chatId == null) {
            logger.warn {
                "⚠️  기본 Telegram 사용자 chatId 형식 오류: ${properties.chatId}"
            }
            return@ApplicationRunner
        }

        // TelegramRegistrationService를 통해 등록 (기존 로직 재사용)
        try {
            telegramRegistrationService.register(userId, chatId)
            logger.info {
                "✅ 기본 Telegram 사용자 자동 등록 완료: userId=$userId, chatId=$chatId"
            }
            logger.warn {
                "⚠️  프로덕션 환경에서는 환경변수를 제거하고 API를 사용하세요"
            }
        } catch (e: Exception) {
            logger.error(e) {
                "❌ 기본 Telegram 사용자 자동 등록 실패: userId=$userId"
            }
        }
    }
}
