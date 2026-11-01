package com.eventfulcommerce.product.repository

import com.eventfulcommerce.product.domain.entity.OfficialSeller
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface OfficialSellerRepository : JpaRepository<OfficialSeller, UUID> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO official_seller (id, display_name) VALUES (:id, :name) " +
        "ON CONFLICT (id) DO UPDATE SET display_name = EXCLUDED.display_name", nativeQuery = true)
    fun provision(@Param("id") id: UUID, @Param("name") name: String): Int
}
