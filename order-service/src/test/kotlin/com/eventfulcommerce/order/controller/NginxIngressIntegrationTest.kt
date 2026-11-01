package com.eventfulcommerce.order.controller

import com.eventfulcommerce.order.exception.GlobalExceptionHandler
import com.eventfulcommerce.order.request.OrderItemRequest
import com.eventfulcommerce.order.request.OrdersRequest
import com.eventfulcommerce.order.service.OrdersService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.eventfulcommerce.common.*
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.order.domain.entity.Orders
import com.eventfulcommerce.order.domain.entity.ProductReadModel
import com.eventfulcommerce.order.repository.OrdersRepository
import com.eventfulcommerce.order.repository.ProductReadModelRepository
import com.eventfulcommerce.order.message.ProductEventsConsumer
import com.eventfulcommerce.order.service.InventoryReservationService
import com.eventfulcommerce.order.service.OrderSagaService
import com.eventfulcommerce.order.service.OrderCancelService
import com.eventfulcommerce.order.service.OrderCancelExecutor
import com.eventfulcommerce.common.metrics.EventfulBusinessMetrics
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.redisson.spring.starter.RedissonAutoConfigurationV2
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import org.testcontainers.Testcontainers
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Testcontainers as ContainerTests
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import com.fasterxml.jackson.databind.ObjectMapper

@ContainerTests(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [IngressTestConfig::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.profiles.active=test", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false"])
class NginxIngressIntegrationTest {
    @LocalServerPort private var port: Int = 0
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var orders: OrdersService
    @Autowired lateinit var products: ProductReadModelRepository
    @Autowired lateinit var inventory: InventoryReservationService
    @Autowired lateinit var consumer: ProductEventsConsumer
    @Autowired lateinit var orderRepository: OrdersRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @MockBean lateinit var cancellation: OrderCancelService
    @MockBean lateinit var cancelExecutor: OrderCancelExecutor
    @MockBean lateinit var metrics: EventfulBusinessMetrics

    @Test fun `실제 Nginx는 주문 필수 헤더를 허용하고 조작 IP를 덮어쓴다`() {
        Testcontainers.exposeHostPorts(port)
        val config = Files.readString(Path.of("../nginx/nginx.conf"))
            .replace("api-gateway:8080", "host.testcontainers.internal:$port")
        GenericContainer<Nothing>("nginx:alpine").apply {
            withCopyToContainer(Transferable.of(config), "/etc/nginx/nginx.conf")
            withExposedPorts(80)
        }.use { nginx ->
            nginx.start()
            assertEquals(0, nginx.execInContainer("nginx", "-t").exitCode)
            val base = "http://${nginx.host}:${nginx.getMappedPort(80)}"
            val http = HttpClient.newHttpClient()
            fun send(builder: HttpRequest.Builder) = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val preflight = send(HttpRequest.newBuilder(URI("$base/orders"))
                .header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,authorization,idempotency-key")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()))
            assertEquals(204, preflight.statusCode())
            assertEquals("http://localhost:3000", preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow())
            assertTrue(preflight.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().lowercase().contains("idempotency-key"))
            val forbidden = send(HttpRequest.newBuilder(URI("$base/orders")).header("Origin", "https://untrusted.invalid")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()))
            assertTrue(forbidden.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
            val product = products.saveAndFlush(ProductReadModel(UUID.randomUUID(), UUID.randomUUID(), "기존 상품", 10_000, 10, "FLOWERS"))
            inventory.initializeStock(product.productId.toString(), 10)
            val request = OrdersRequest(listOf(OrderItemRequest(product.productId, 1)))
            fun post() = HttpRequest.newBuilder(URI("$base/orders")).header("Origin", "http://localhost:3000")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
            val created = send(post().header("Idempotency-Key", "ingress-order-key"))
            assertEquals(200, created.statusCode(), created.body())
            val firstOrder = UUID.fromString(mapper.readTree(created.body())["orderId"].asText())
            assertEquals(10_000L, orders.getOrder(firstOrder, IngressTestConfig.USER).totalPaymentAmount)
            val changed = OutboxEventMessage(UUID.randomUUID(), "PRODUCT", product.productId, "PRODUCT_UPDATED", Instant.now(),
                mapper.writeValueAsString(ProductUpdatedPayload(product.productId, product.sellerId, "변경 상품", 20_000, "FLOWERS", 2)))
            consumer.consume(mapper.writeValueAsString(changed))
            consumer.consume(mapper.writeValueAsString(changed))
            consumer.consume(mapper.writeValueAsString(changed.copy(eventId = UUID.randomUUID(), payload =
                mapper.writeValueAsString(ProductUpdatedPayload(product.productId, product.sellerId, "늦은 변경", 5_000, "FLOWERS", 1)))))
            val changedOrder = send(post().header("Idempotency-Key", "changed-order-key"))
            assertEquals(200, changedOrder.statusCode(), changedOrder.body())
            val secondOrder = UUID.fromString(mapper.readTree(changedOrder.body())["orderId"].asText())
            assertEquals(20_000L, orders.getOrder(secondOrder, IngressTestConfig.USER).totalPaymentAmount)
            assertEquals(10_000L, orders.getOrder(firstOrder, IngressTestConfig.USER).sellerOrders.single().items.single().unitPrice)
            assertEquals(2L, orderRepository.count())
            assertEquals(4L, outbox.count())
            assertEquals(8, inventory.getAvailableStock(product.productId.toString()))
            val missing = send(post())
            assertEquals(400, missing.statusCode(), missing.body())
            var actualIp: String? = null
            listOf("198.51.100.123", "198.51.100.124").forEach { forged ->
                val response = send(HttpRequest.newBuilder(URI("$base/test-client-ip"))
                    .header("X-Real-IP", forged).header("X-Forwarded-For", "$forged, 203.0.113.2")
                    .header("Forwarded", "for=$forged").header("Proxy-Client-IP", forged).GET())
                assertEquals(200, response.statusCode())
                val headers = mapper.readTree(response.body())
                val ip = headers["real"].asText()
                assertNotEquals(forged, ip)
                assertEquals(ip, headers["forwardedFor"].asText())
                assertTrue(headers["forwarded"].isNull)
                if (actualIp == null) actualIp = ip else assertEquals(actualIp, ip)
            }
        }
    }
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmStatic val redis = GenericContainer<Nothing>("redis:7.2-alpine").apply { withExposedPorts(6379) }
        @DynamicPropertySource @JvmStatic fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
@EntityScan(basePackageClasses = [Orders::class, OutboxEvent::class, ProcessedEvent::class])
@EnableJpaRepositories(basePackageClasses = [OrdersRepository::class, OutboxEventRepository::class, ProcessedEventRepository::class])
@EnableAutoConfiguration(exclude = [RedisAutoConfiguration::class,
    RedissonAutoConfigurationV2::class, SecurityAutoConfiguration::class, UserDetailsServiceAutoConfiguration::class,
    org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration::class])
@Import(OrderController::class, GlobalExceptionHandler::class, ClientIpEchoController::class,
    OrdersService::class, OrderSagaService::class, ProductEventsConsumer::class, IdempotencyHandler::class,
    com.eventfulcommerce.order.service.MixedOrdersService::class,
    com.eventfulcommerce.order.service.MixedOrderStateService::class,
    com.eventfulcommerce.order.service.InventoryServiceClient::class,
    OutboxEventService::class, OutboxRetryPolicy::class, OutboxPublisherProperties::class)
class IngressTestConfig {
    @Bean fun orderClock(): java.time.Clock = java.time.Clock.systemUTC()
    @Bean fun redisConnectionFactory() = LettuceConnectionFactory(
        NginxIngressIntegrationTest.redis.host, NginxIngressIntegrationTest.redis.getMappedPort(6379))
    @Bean fun redisTemplate(factory: LettuceConnectionFactory) = StringRedisTemplate(factory)
    @Bean fun inventory(template: StringRedisTemplate) = InventoryReservationService(template, 3_600)
    @Bean fun authentication() = object : OncePerRequestFilter() {
        override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(USER, null, emptyList())
            try { chain.doFilter(req, res) } finally { SecurityContextHolder.clearContext() }
        }
    }
    companion object { val USER: UUID = UUID.randomUUID() }
}
@RestController
class ClientIpEchoController {
    @GetMapping("/test-client-ip")
    fun headers(request: HttpServletRequest): Map<String, String?> = mapOf(
        "real" to request.getHeader("X-Real-IP"), "forwardedFor" to request.getHeader("X-Forwarded-For"),
        "forwarded" to request.getHeader("Forwarded"))
}
