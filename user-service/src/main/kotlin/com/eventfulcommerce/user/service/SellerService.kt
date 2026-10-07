package com.eventfulcommerce.user.service

import com.eventfulcommerce.user.domain.entity.Seller
import com.eventfulcommerce.user.domain.repository.SellerRepository
import com.eventfulcommerce.user.exception.DuplicateBusinessNumberException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SellerService(
    private val sellerRepository: SellerRepository
) {

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
}
