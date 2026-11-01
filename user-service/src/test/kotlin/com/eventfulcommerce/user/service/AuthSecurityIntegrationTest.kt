package com.eventfulcommerce.user.service

import com.eventfulcommerce.common.auth.JwtConfig
import com.eventfulcommerce.common.auth.JwtTokenProvider
import com.eventfulcommerce.common.auth.UserRole
import com.eventfulcommerce.user.config.RedisConfig
import com.eventfulcommerce.user.domain.entity.User
import com.eventfulcommerce.user.domain.entity.Seller
import com.eventfulcommerce.user.domain.repository.UserRepository
import com.eventfulcommerce.user.domain.repository.SellerRepository
import com.eventfulcommerce.user.domain.repository.AuditLogRepository
import com.eventfulcommerce.user.dto.LoginRequest
import com.eventfulcommerce.user.dto.RefreshRequest
import com.eventfulcommerce.user.exception.InvalidCredentialsException
import com.eventfulcommerce.user.exception.InvalidTokenException
import com.eventfulcommerce.user.interceptor.RateLimitInterceptor
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [AuthSecurityTestConfig::class], webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=test", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"])
class AuthSecurityIntegrationTest {
    @Autowired lateinit var users: UserRepository
    @Autowired lateinit var sellers: SellerRepository
    @Autowired lateinit var audit: AuditLogRepository
    @Autowired lateinit var auth: AuthService
    @Autowired lateinit var reset: PasswordResetService
    @Autowired lateinit var attempts: LoginAttemptService
    @Autowired lateinit var rate: RateLimitService
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("redisTemplate") lateinit var redis: RedisTemplate<String, String>
    @Autowired lateinit var encoder: PasswordEncoder
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var tm: PlatformTransactionManager
    @MockBean lateinit var email: EmailService
    @SpyBean lateinit var refresh: RefreshTokenService
    @SpyBean lateinit var auditService: AuditLogService
    private val request get() = MockHttpServletRequest().apply { addHeader("X-Real-IP", "203.0.113.7") }

    @BeforeEach fun clean() {
        audit.deleteAll(); users.deleteAll(); sellers.deleteAll()
        redis.connectionFactory!!.connection.use { it.serverCommands().flushDb() }
    }
    private fun user() = users.saveAndFlush(User(email = "user@test.invalid", password = encoder.encode("old-password"), name = "사용자"))
    private fun seller() = sellers.saveAndFlush(Seller(email = "seller@test.invalid", password = encoder.encode("old-password"),
        name = "판매자", businessName = "상점", businessNumber = "1234567890", bankAccount = "test", bankCode = "test"))
    private fun resetToken(id: UUID): String = UUID.randomUUID().toString().also {
        redis.opsForValue().set("password_reset:$it", id.toString(), Duration.ofMinutes(30))
    }

    @Test fun `USER 실패 감사와 5회 계정 잠금은 인증 예외 뒤에도 DB에 커밋된다`() {
        val u = user()
        repeat(5) { assertThrows(InvalidCredentialsException::class.java) { auth.loginUser(LoginRequest(u.email, "wrong"), request) } }
        assertTrue(users.findById(u.id).orElseThrow().accountLockedUntil!!.isAfter(Instant.now()))
        assertEquals(5L, audit.count())
        assertTrue(audit.findAll().all { !it.success && it.ipAddress == "203.0.113.7" })
    }
    @Test fun `SELLER 실패 감사와 5회 계정 잠금도 커밋된다`() {
        val s = seller()
        repeat(5) { assertThrows(InvalidCredentialsException::class.java) { auth.loginSeller(LoginRequest(s.email, "wrong"), request) } }
        assertTrue(sellers.findById(s.id).orElseThrow().accountLockedUntil!!.isAfter(Instant.now()))
        assertEquals(5L, audit.count())
    }
    @Test fun `없는 계정과 IP 차단의 실패 로그도 남는다`() {
        assertThrows(InvalidCredentialsException::class.java) { auth.loginUser(LoginRequest("absent@test.invalid", "wrong"), request) }
        repeat(4) { attempts.recordFailure("203.0.113.7") }
        assertThrows(InvalidCredentialsException::class.java) { auth.loginSeller(LoginRequest("absent@test.invalid", "wrong"), request) }
        assertEquals(2L, audit.count())
    }
    @Test fun `USER와 SELLER 정상 로그인 및 갱신을 보존한다`() {
        val u = user(); val s = seller()
        val userTokens = auth.loginUser(LoginRequest(u.email, "old-password"), request)
        val sellerTokens = auth.loginSeller(LoginRequest(s.email, "old-password"), request)
        listOf(userTokens, sellerTokens).forEach {
            assertTrue(jwt.validateToken(it.accessToken))
            assertEquals(it.userId, auth.refreshAccessToken(RefreshRequest(it.refreshToken)).userId)
        }
        assertEquals(2L, audit.count())
        assertTrue(audit.findAll().all { it.success })
    }
    @Test fun `재설정 뒤 이전 refresh는 거부하고 기존 access는 만료까지 유지한다`() {
        val u = user(); val tokens = auth.loginUser(LoginRequest(u.email, "old-password"), request)
        val token = resetToken(u.id)
        reset.resetPassword(token, "new-password", request)
        assertTrue(encoder.matches("new-password", users.findById(u.id).orElseThrow().password))
        assertThrows(InvalidTokenException::class.java) { auth.refreshAccessToken(RefreshRequest(tokens.refreshToken)) }
        assertFalse(redis.hasKey("password_reset:$token"))
        assertTrue(jwt.validateToken(tokens.accessToken))
        assertThrows(InvalidCredentialsException::class.java) { auth.loginUser(LoginRequest(u.email, "old-password"), request) }
        val newTokens = auth.loginUser(LoginRequest(u.email, "new-password"), request)
        assertTrue(jwt.validateToken(newTokens.accessToken))
        assertNotEquals(tokens.refreshToken, newTokens.refreshToken)
        assertThrows(InvalidTokenException::class.java) { auth.refreshAccessToken(RefreshRequest(tokens.refreshToken)) }
    }
    @Test fun `refresh 폐기 실패는 비밀번호와 재설정 토큰을 보존한다`() {
        val u = user(); val tokens = auth.loginUser(LoginRequest(u.email, "old-password"), request)
        val token = resetToken(u.id)
        Mockito.doThrow(IllegalStateException("Redis 실패")).`when`(refresh).deleteRefreshToken(u.id, UserRole.USER)
        assertThrows(IllegalStateException::class.java) { reset.resetPassword(token, "new-password", request) }
        assertTrue(encoder.matches("old-password", users.findById(u.id).orElseThrow().password))
        assertTrue(redis.hasKey("password_reset:$token"))
        assertEquals(u.id, auth.refreshAccessToken(RefreshRequest(tokens.refreshToken)).userId)
    }
    @Test fun `폐기 후 DB 롤백은 비밀번호를 보존하고 이전 refresh를 되살리지 않는다`() {
        val u = user(); val tokens = auth.loginUser(LoginRequest(u.email, "old-password"), request)
        val token = resetToken(u.id)
        val req = request
        Mockito.doThrow(IllegalStateException("DB 감사 저장 실패")).`when`(auditService).logPasswordChange(u.id, req)
        assertThrows(IllegalStateException::class.java) { reset.resetPassword(token, "new-password", req) }
        assertTrue(encoder.matches("old-password", users.findById(u.id).orElseThrow().password))
        assertThrows(InvalidTokenException::class.java) { auth.refreshAccessToken(RefreshRequest(tokens.refreshToken)) }
        assertFalse(redis.hasKey("password_reset:$token"))
    }
    @Test fun `재설정과 경합한 옛 비밀번호 로그인은 새 refresh를 남기지 않는다`() {
        val u = user(); val token = resetToken(u.id)
        val held = CountDownLatch(1); val release = CountDownLatch(1); val started = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val resetting = pool.submit { TransactionTemplate(tm).executeWithoutResult {
                reset.resetPassword(token, "new-password", request); held.countDown(); check(release.await(10, TimeUnit.SECONDS))
            } }
            assertTrue(held.await(10, TimeUnit.SECONDS))
            val login = pool.submit { started.countDown()
                assertThrows(InvalidCredentialsException::class.java) { auth.loginUser(LoginRequest(u.email, "old-password"), request) }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { login.get(250, TimeUnit.MILLISECONDS) }
            release.countDown(); resetting.get(10, TimeUnit.SECONDS); login.get(10, TimeUnit.SECONDS)
            assertFalse(redis.hasKey("refresh_token:user:${u.id}"))
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun `동시 로그인 실패 횟수와 만료를 Redis가 보존한다`() {
        parallel(160) { attempts.recordFailure("counter-ip"); attempts.recordFailureByEmail("counter@test.invalid") }
        assertEquals(160, attempts.getAttempts("counter-ip"))
        assertEquals(160, attempts.getAttemptsByEmail("counter@test.invalid"))
        assertTrue(attempts.getRemainingLockTime("counter-ip") > 0)
        assertTrue(redis.getExpire("login_attempt:email:counter@test.invalid") > 0)
    }
    @Test fun `동시 요청 중 정확히 100건만 허용하며 만료 경계 이후 복구한다`() {
        val allowed = java.util.concurrent.atomic.AtomicInteger()
        parallel(240) { if (rate.checkRateLimit("rate-ip")) allowed.incrementAndGet() }
        assertEquals(100, allowed.get()); assertEquals(100, rate.getCurrentCount("rate-ip"))
        assertTrue(rate.getResetTime("rate-ip") > 0)
        redis.expire("rate_limit:rate-ip", Duration.ofMillis(20))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (redis.hasKey("rate_limit:rate-ip") && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(rate.checkRateLimit("rate-ip")); assertEquals(1, rate.getCurrentCount("rate-ip"))
        assertTrue(rate.getResetTime("rate-ip") > 0)
        redis.opsForValue().set("rate_limit:legacy-ip", "100")
        assertFalse(rate.checkRateLimit("legacy-ip")); assertTrue(rate.getResetTime("legacy-ip") > 0)
    }
    @Test fun `전달 IP 조작은 요청 제한 로그인 제한 감사의 키를 바꾸지 못한다`() {
        val u = user(); val interceptor = RateLimitInterceptor(rate, ObjectMapper().findAndRegisterModules())
        listOf("198.51.100.1", "198.51.100.2").forEach { fake ->
            val req = request.apply { addHeader("X-Forwarded-For", "$fake, 203.0.113.7"); addHeader("Proxy-Client-IP", fake) }
            assertTrue(interceptor.preHandle(req, MockHttpServletResponse(), Any()))
            assertThrows(InvalidCredentialsException::class.java) { auth.loginUser(LoginRequest(u.email, "wrong"), req) }
            assertEquals(0, rate.getCurrentCount(fake)); assertEquals(0, attempts.getAttempts(fake))
        }
        assertEquals(2, rate.getCurrentCount("203.0.113.7")); assertEquals(2, attempts.getAttempts("203.0.113.7"))
        assertTrue(audit.findAll().all { it.ipAddress == "203.0.113.7" })
    }
    private fun parallel(count: Int, action: () -> Unit) {
        val pool = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        try {
            val futures = (1..count).map { pool.submit { check(start.await(5, TimeUnit.SECONDS)); action() } }
            start.countDown(); futures.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmStatic val redisServer = GenericContainer<Nothing>("redis:7.2-alpine").apply { withExposedPorts(6379) }
        @DynamicPropertySource @JvmStatic fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EntityScan(basePackageClasses = [User::class])
@EnableJpaRepositories(basePackageClasses = [UserRepository::class])
@Import(UserService::class, SellerService::class, AuthService::class, PasswordResetService::class,
    RefreshTokenService::class, TokenBlacklistService::class, AuditLogService::class,
    AccountLockService::class, LoginAttemptService::class, RateLimitService::class, RedisConfig::class)
class AuthSecurityTestConfig {
    @Bean fun jwtConfig() = JwtConfig(secret = "test-secret-".repeat(8))
    @Bean fun jwt(config: JwtConfig) = JwtTokenProvider(config)
    @Bean fun encoder(): PasswordEncoder = BCryptPasswordEncoder(4)
    @Bean fun redisConnectionFactory() = LettuceConnectionFactory(
        AuthSecurityIntegrationTest.redisServer.host, AuthSecurityIntegrationTest.redisServer.getMappedPort(6379))
}
