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
    
    /**
     * Seller 조회 (ID로)
     */
    fun findById(sellerId: UUID): Seller? {
        return sellerRepository.findById(sellerId).orElse(null)
    }
    
    /**
     * Seller 조회 (userId로)
     */
    fun findByUserId(userId: UUID): Seller? {
        return sellerRepository.findByUserId(userId)
    }
    
    /**
     * 사업자 등록번호 중복 확인
     */
    fun existsByBusinessNumber(businessNumber: String): Boolean {
        return sellerRepository.existsByBusinessNumber(businessNumber)
    }
    
    /**
     * 사업자 등록번호 중복 체크 후 예외 발생
     */
    fun validateBusinessNumber(businessNumber: String) {
        if (existsByBusinessNumber(businessNumber)) {
            throw DuplicateBusinessNumberException(businessNumber)
        }
    }
    
    /**
     * Seller 저장
     */
    @Transactional
    fun save(seller: Seller): Seller {
        return sellerRepository.save(seller)
    }
    
    /**
     * Seller 삭제
     */
    @Transactional
    fun delete(sellerId: UUID) {
        sellerRepository.deleteById(sellerId)
        logger.info { "🗑️ Seller 삭제: sellerId=$sellerId" }
    }
}
