package com.eventfulcommerce.order.service

import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("동시성 스트레스 테스트")
class ConcurrencyStressTest {

    @Autowired
    private lateinit var ordersService: OrdersService

    @Autowired
    private lateinit var ordersRepository: OrdersRepository

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    companion object {
        @Container
        val postgresContainer = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("order_service")
            withUsername("postgres")
            withPassword("postgres")
        }

        @Container
        val redisContainer = GenericContainer<Nothing>("redis:7-alpine").apply {
            withExposedPorts(6379)
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgresContainer::getJdbcUrl)
            registry.add("spring.datasource.username", postgresContainer::getUsername)
            registry.add("spring.datasource.password", postgresContainer::getPassword)
            
            registry.add("spring.data.redis.host", redisContainer::getHost)
            registry.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
            
            registry.add("spring.kafka.bootstrap-servers") { "localhost:9999" }
        }
    }

    @BeforeEach
    fun setUp() {
        redisTemplate.opsForValue().set("stock:default", "100")
        redisTemplate.opsForValue().set("holdCount:default", "0")
        ordersRepository.deleteAll()
    }

    @AfterEach
    fun tearDown() {
        ordersRepository.deleteAll()
        redisTemplate.delete("stock:default")
        redisTemplate.delete("holdCount:default")
        val holdKeys = redisTemplate.keys("hold:*")
        if (!holdKeys.isNullOrEmpty()) {
            redisTemplate.delete(holdKeys)
        }
    }

    @Test
    @DisplayName("100개 동시 요청 - 재고 100개")
    fun `should handle 100 concurrent orders with 100 stock`() {
        // Given: 재고 100개
        val threadCount = 100
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        // When: 100개 스레드에서 동시에 주문
        repeat(threadCount) { index ->
            executor.submit {
                try {
                    val userId = UUID.randomUUID()
                    val request = listOf(
                        OrdersRequest(
                            userId = userId.toString(),
                            totalAmount = 10000L
                        )
                    )
                    
                    ordersService.orders(request)
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // Then: 100개 모두 성공해야 함
        assertEquals(100, successCount.get(), "100개 모두 성공해야 함")
        assertEquals(0, failureCount.get(), "실패가 없어야 함")

        // 재고 확인
        val remainingStock = redisTemplate.opsForValue().get("stock:default")?.toLong()
        assertEquals(0L, remainingStock, "재고가 0이어야 함")

        // DB 확인
        val orders = ordersRepository.findAll()
        assertEquals(100, orders.size)
        assertTrue(orders.all { it.status == OrdersStatus.ORDER_RESERVED })
    }

    @Test
    @DisplayName("200개 동시 요청 - 재고 100개 (경쟁 상황)")
    fun `should handle 200 concurrent orders with 100 stock - race condition`() {
        // Given: 재고 100개, 요청 200개
        val threadCount = 200
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        // When: 200개 스레드에서 동시에 주문
        repeat(threadCount) { index ->
            executor.submit {
                try {
                    val userId = UUID.randomUUID()
                    val request = listOf(
                        OrdersRequest(
                            userId = userId.toString(),
                            totalAmount = 10000L
                        )
                    )
                    
                    ordersService.orders(request)
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // Then: 100개만 성공, 100개는 실패
        assertEquals(100, successCount.get(), "100개만 성공해야 함")
        assertEquals(100, failureCount.get(), "100개는 실패해야 함")

        // 재고 확인
        val remainingStock = redisTemplate.opsForValue().get("stock:default")?.toLong()
        assertEquals(0L, remainingStock, "재고가 0이어야 함")

        // DB 확인
        val orders = ordersRepository.findAll()
        assertEquals(100, orders.size, "성공한 100개만 저장되어야 함")
        assertTrue(orders.all { it.status == OrdersStatus.ORDER_RESERVED })
    }

    @Test
    @DisplayName("동일 상품 50명이 동시 구매 - 재고 10개")
    fun `should handle 50 users buying same product with 10 stock`() {
        // Given: 재고 10개만
        redisTemplate.opsForValue().set("stock:default", "10")

        val threadCount = 50
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        // When
        repeat(threadCount) {
            executor.submit {
                try {
                    val userId = UUID.randomUUID()
                    val request = listOf(
                        OrdersRequest(
                            userId = userId.toString(),
                            totalAmount = 10000L
                        )
                    )
                    
                    ordersService.orders(request)
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // Then: 정확히 10개만 성공
        assertEquals(10, successCount.get(), "10개만 성공해야 함")
        assertEquals(40, failureCount.get(), "40개는 실패해야 함")

        // 재고 확인 (0이어야 함)
        val remainingStock = redisTemplate.opsForValue().get("stock:default")?.toLong()
        assertEquals(0L, remainingStock)
    }

    @Test
    @DisplayName("오버셀링 방지 테스트 - 재고보다 많은 주문")
    fun `should prevent overselling with concurrent orders`() {
        // Given: 재고 5개
        redisTemplate.opsForValue().set("stock:default", "5")

        val threadCount = 20
        val successCount = AtomicInteger(0)

        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        // When: 20개 주문 시도
        repeat(threadCount) {
            executor.submit {
                try {
                    val userId = UUID.randomUUID()
                    val request = listOf(
                        OrdersRequest(
                            userId = userId.toString(),
                            totalAmount = 10000L
                        )
                    )
                    
                    ordersService.orders(request)
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    // 실패는 정상
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // Then: 정확히 5개만 성공
        assertEquals(5, successCount.get(), "재고 수만큼만 성공해야 함")

        val remainingStock = redisTemplate.opsForValue().get("stock:default")?.toLong()
        assertEquals(0L, remainingStock, "재고가 0이어야 함")

        // 중요: 재고 수와 성공 수가 정확히 일치해야 함
        val dbCount = ordersRepository.count()
        assertEquals(5L, dbCount, "DB에도 정확히 5개만 있어야 함")
    }

    @Test
    @DisplayName("트래픽 시뮬레이션 - 1000개 요청")
    fun `should handle high traffic - 1000 concurrent requests`() {
        // Given: 재고 500개
        redisTemplate.opsForValue().set("stock:default", "500")

        val threadCount = 1000
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        val executor = Executors.newFixedThreadPool(100) // 스레드 풀 100개
        val latch = CountDownLatch(threadCount)

        val startTime = System.currentTimeMillis()

        // When: 1000개 주문
        repeat(threadCount) {
            executor.submit {
                try {
                    val userId = UUID.randomUUID()
                    val request = listOf(
                        OrdersRequest(
                            userId = userId.toString(),
                            totalAmount = 10000L
                        )
                    )
                    
                    ordersService.orders(request)
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        // Then
        assertEquals(500, successCount.get(), "500개 성공")
        assertEquals(500, failureCount.get(), "500개 실패")

        val remainingStock = redisTemplate.opsForValue().get("stock:default")?.toLong()
        assertEquals(0L, remainingStock)

        // 성능 확인 (1000개 요청을 30초 이내에 처리)
        assertTrue(duration < 30000, "30초 이내에 처리되어야 함: ${duration}ms")
        
        println("✅ 1000개 요청 처리 시간: ${duration}ms")
        println("✅ 초당 처리량: ${1000.0 / (duration / 1000.0)} TPS")
    }
}
