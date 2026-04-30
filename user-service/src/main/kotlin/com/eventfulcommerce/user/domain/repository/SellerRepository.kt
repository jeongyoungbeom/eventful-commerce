package com.eventfulcommerce.user.domain.repository

import com.eventfulcommerce.user.domain.entity.Seller
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SellerRepository : JpaRepository<Seller, UUID> {
    
    fun findByUserId(userId: UUID): Seller?
    
    fun existsByBusinessNumber(businessNumber: String): Boolean
}
