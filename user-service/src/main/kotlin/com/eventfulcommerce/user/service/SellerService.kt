package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.domain.entity.Seller
import com.eventfulcommerce.user.domain.repository.SellerRepository
import com.eventfulcommerce.user.exception.DuplicateBusinessNumberException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class SellerService(
    private val sellerRepository: SellerRepository
) {

    fun findById(sellerId: UUID): Seller? =
        sellerRepository.findById(sellerId).orElse(null)

    fun findByEmail(email: String): Seller? =
        sellerRepository.findByEmail(email)

    fun existsByEmail(email: String): Boolean =
        sellerRepository.existsByEmail(email)

    fun existsByBusinessNumber(businessNumber: String): Boolean =
        sellerRepository.existsByBusinessNumber(businessNumber)

    fun validateBusinessNumber(businessNumber: String) {
        if (existsByBusinessNumber(businessNumber)) {
            throw DuplicateBusinessNumberException(businessNumber)
        }
    }

    @Transactional
    fun save(seller: Seller): Seller =
        sellerRepository.save(seller)

    @Transactional
    fun delete(sellerId: UUID) {
        sellerRepository.deleteById(sellerId)
        logger.info { "Seller 삭제: sellerId=$sellerId" }
    }
}
