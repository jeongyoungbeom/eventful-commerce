package com.eventfulcommerce.user.domain.entity

import java.time.Instant
import java.util.UUID

interface Lockable {
    val id: UUID
    var accountLockedUntil: Instant?
    fun isAccountLocked(): Boolean
    fun lockAccount(until: Instant)
    fun unlockAccount()
}
