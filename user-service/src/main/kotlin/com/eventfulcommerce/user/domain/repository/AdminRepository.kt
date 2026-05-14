package com.eventfulcommerce.user.domain.repository

import com.eventfulcommerce.user.domain.entity.Admin
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AdminRepository : JpaRepository<Admin, UUID> {

    fun findByEmail(email: String): Admin?

    fun existsByEmail(email: String): Boolean
}
