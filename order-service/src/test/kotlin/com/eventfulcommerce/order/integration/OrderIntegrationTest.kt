package com.eventfulcommerce.order.integration

import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.repository.OrdersRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.junit.jupiter.api.Assertions.*
import java.util.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("주문 통합 테스트")
@ActiveProfiles("test")
class OrderIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var ordersRepository: OrdersRepository

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

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
            
            // Kafka 비활성화
            registry.add("spring.kafka.bootstrap-servers") { "localhost:9999" }
        }
    }

    @BeforeEach
    fun setUp() {
        // Redis 초기 재고 설정
        redisTemplate.opsForValue().set("stock:default", "100")
        redisTemplate.opsForValue().set("holdCount:default", "0")
    }

    @AfterEach
    fun tearDown() {
        // 테스트 데이터 정리
        ordersRepository.deleteAll()
        
        // Redis 정리
        redisTemplate.delete("stock:default")
        redisTemplate.delete("holdCount:default")
        val holdKeys = redisTemplate.keys("hold:*")
        if (!holdKeys.isNullOrEmpty()) {
            redisTemplate.delete(holdKeys)
        }
    }

    @Test
    @DisplayName("API를 통한 주문 생성이 성공한다")
    fun `should create order successfully via API`() {
        // Given
        val userId = UUID.randomUUID()
        val request = listOf(
            OrdersRequest(
                userId = userId.toString(),
                totalAmount = 10000L
            )
        )

        // When & Then
        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)

        // 주문이 DB에 저장되었는지 확인
        val orders = ordersRepository.findAll()
        assertEquals(1, orders.size)
        assertEquals(OrdersStatus.ORDER_RESERVED, orders[0].status)
        assertNotNull(orders[0].reservationId)

        // Redis 재고 확인
        val stock = redisTemplate.opsForValue().get("stock:default")?.toLong()
        assertEquals(99L, stock)
    }

    @Test
    @DisplayName("재고가 부족하면 409 Conflict를 반환하고 DB에는 남지 않는다(롤백)")
    fun `should return 409 Conflict and rollback when stock is insufficient`() {
        // Given
        redisTemplate.opsForValue().set("stock:default", "0")

        val userId = UUID.randomUUID()
        val request = listOf(OrdersRequest(userId = userId.toString(), totalAmount = 10000L))

        // When & Then
        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isConflict)

        // 롤백 정책이므로 DB에는 남으면 안 됨
        val orders = ordersRepository.findAll()
        assertEquals(0, orders.size)
    }


    @Test
    @DisplayName("복수 주문 중 일부만 실패해도 409 Conflict 반환")
    fun `should handle partial success in batch order`() {
        // Given - 재고를 1로 설정
        redisTemplate.opsForValue().set("stock:default", "1")

        val userId1 = UUID.randomUUID()
        val userId2 = UUID.randomUUID()
        
        val request = listOf(
            OrdersRequest(userId1.toString(), 10000L),
            OrdersRequest(userId2.toString(), 20000L)
        )

        // When
        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isConflict)

        // DB 확인
        val orders = ordersRepository.findAll()
        assertEquals(0, orders.size)
    }

    @Test
    @DisplayName("복수 주문이 모두 성공한다")
    fun `should create multiple orders successfully`() {
        // Given
        val userId1 = UUID.randomUUID()
        val userId2 = UUID.randomUUID()
        val userId3 = UUID.randomUUID()
        
        val request = listOf(
            OrdersRequest(userId1.toString(), 10000L),
            OrdersRequest(userId2.toString(), 20000L),
            OrdersRequest(userId3.toString(), 30000L)
        )

        // When
        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)

        // DB 확인
        val orders = ordersRepository.findAll()
        assertEquals(3, orders.size)
        assertTrue(orders.all { it.status == OrdersStatus.ORDER_RESERVED })

        // Redis 재고 확인 (100 - 3 = 97)
        val stock = redisTemplate.opsForValue().get("stock:default")?.toLong()
        assertEquals(97L, stock)
    }

    @Test
    @DisplayName("재고가 0이면 모든 주문이 실패한다")
    fun `should fail all orders when stock is zero`() {
        // Given
        redisTemplate.opsForValue().set("stock:default", "0")

        val userId1 = UUID.randomUUID()
        val userId2 = UUID.randomUUID()
        
        val request = listOf(
            OrdersRequest(userId1.toString(), 10000L),
            OrdersRequest(userId2.toString(), 20000L)
        )

        // When
        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isConflict)
    }
}
