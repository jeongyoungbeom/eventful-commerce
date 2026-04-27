package com.eventfulcommerce.notification.domain.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "user_chat_ids",
    indexes = [Index(name = "idx_user_id", columnList = "userId", unique = true)]
)
class UserChatId(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    
    @Column(nullable = false, unique = true)
    val userId: UUID,
    
    @Column(nullable = false)
    var chatId: Long,
    
    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)
