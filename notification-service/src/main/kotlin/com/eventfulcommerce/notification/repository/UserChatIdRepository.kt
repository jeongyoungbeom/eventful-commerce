package com.eventfulcommerce.notification.repository

import com.eventfulcommerce.notification.domain.entity.UserChatId
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserChatIdRepository : JpaRepository<UserChatId, UUID> {
    fun findByUserId(userId: UUID): UserChatId?
    fun findByChatId(chatId: Long): UserChatId?
}
