package com.eventfulcommerce.user.domain.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import com.eventfulcommerce.user.domain.entity.Seller
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SellerRepository : JpaRepository<Seller, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from Seller account where account.email = :email")
    fun findByEmailForUpdate(@Param("email") email: String): Seller?


    fun findByEmail(email: String): Seller?

    fun existsByEmail(email: String): Boolean

    fun existsByBusinessNumber(businessNumber: String): Boolean
}
