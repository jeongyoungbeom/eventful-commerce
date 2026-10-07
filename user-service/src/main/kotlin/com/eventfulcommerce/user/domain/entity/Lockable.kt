package com.eventfulcommerce.user.domain.entity

import java.time.Instant
import java.util.UUID

interface Lockable {
    val id: UUID
    var accountLockedUntil: Instant?

    fun lockAccount(until: Instant)
    fun unlockAccount()
}
