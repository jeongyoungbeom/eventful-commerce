package com.eventfulcommerce.user.config

import com.eventfulcommerce.user.domain.entity.Admin
import com.eventfulcommerce.user.domain.entity.Seller
import com.eventfulcommerce.user.domain.entity.User
import com.eventfulcommerce.user.domain.repository.AdminRepository
import com.eventfulcommerce.user.domain.repository.SellerRepository
import com.eventfulcommerce.user.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.CommandLineRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.util.UUID

// [테스트용] 서비스 기동 시 테스트 계정을 자동 생성합니다.
// 이미 데이터가 존재하면 skip됩니다 (idempotent).
// TEST_SELLER_ID는 product-service DataInitializer와 공유합니다.
@Component
class DataInitializer(
    private val userRepository: UserRepository,
    private val sellerRepository: SellerRepository,
    private val adminRepository: AdminRepository,
    private val passwordEncoder: PasswordEncoder
) : CommandLineRunner {

    companion object {
        val TEST_SELLER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }

    private val logger = KotlinLogging.logger {}

    override fun run(vararg args: String?) {
        createTestUser()
        createTestSeller()
        createTestAdmin()
    }

    private fun createTestUser() {
        if (userRepository.existsByEmail("user@test.com")) return

        userRepository.save(
            User(
                email = "user@test.com",
                password = passwordEncoder.encode("Test1234"),
                name = "테스트유저"
            )
        )
        logger.info { "[테스트용] 테스트 유저 계정 생성 완료 (user@test.com)" }
    }

    private fun createTestSeller() {
        if (sellerRepository.existsByEmail("seller@test.com")) return

        sellerRepository.save(
            Seller(
                id = TEST_SELLER_ID,
                email = "seller@test.com",
                password = passwordEncoder.encode("Test1234"),
                name = "테스트판매자",
                businessName = "테스트꽃가게",
                businessNumber = "000-00-00001",
                bankAccount = "123-456-789012",
                bankCode = "004"
            )
        )
        logger.info { "[테스트용] 테스트 판매자 계정 생성 완료 (seller@test.com, id=$TEST_SELLER_ID)" }
    }

    private fun createTestAdmin() {
        if (adminRepository.existsByEmail("admin@test.com")) return

        adminRepository.save(
            Admin(
                email = "admin@test.com",
                password = passwordEncoder.encode("Test1234"),
                name = "테스트관리자"
            )
        )
        logger.info { "[테스트용] 테스트 관리자 계정 생성 완료 (admin@test.com)" }
    }
}
