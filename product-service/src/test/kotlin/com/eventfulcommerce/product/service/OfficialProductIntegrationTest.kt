package com.eventfulcommerce.product.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.product.config.SecurityConfig
import com.eventfulcommerce.product.controller.OfficialProductController
import com.eventfulcommerce.product.controller.ProductController
import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.dto.CreateProductRequest
import com.eventfulcommerce.product.dto.UpdateProductRequest
import com.eventfulcommerce.product.exception.GlobalExceptionHandler
import com.eventfulcommerce.product.exception.ProductOwnershipException
import com.eventfulcommerce.product.repository.ProductRepository
import com.eventfulcommerce.product.repository.OfficialSellerRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.http.MediaType
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

@Testcontainers
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = [OfficialProductTestConfig::class], properties = ["spring.profiles.active=test",
    "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"])
class OfficialProductIntegrationTest {
    @Autowired lateinit var service: ProductService
    @Autowired lateinit var products: ProductRepository
    @Autowired lateinit var official: OfficialSellerRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var dataSource: DataSource
    private val actor = UUID.randomUUID()
    private fun login(role: String) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(actor, null,
            listOf(SimpleGrantedAuthority("ROLE_$role")))
    }
    private fun request(stock: Int = 0) = CreateProductRequest("공식몰", "설명", 10_000, stock, ProductCategory.FLOWERS)
    @BeforeEach fun clean() { SecurityContextHolder.clearContext(); outbox.deleteAll(); products.deleteAll(); official.deleteAll() }
    @AfterEach fun clearAuthentication() { SecurityContextHolder.clearContext() }

    @Test fun `공식 주체 발급은 HTTP와 서비스 모두 ADMIN만 가능하다`() {
        for (role in listOf("USER", "SELLER")) {
            mvc.perform(put("/products/official/seller").header("X-User-Id", actor).header("X-User-Role", role)
                .contentType(MediaType.APPLICATION_JSON).content("""{"displayName":"공식몰"}"""))
                .andExpect(status().isForbidden)
            login(role)
            assertThrows(AccessDeniedException::class.java) { service.provisionOfficialSeller("공식몰") }
            assertThrows(AccessDeniedException::class.java) { service.createOfficialProduct(request(), null) }
            assertThrows(AccessDeniedException::class.java) { service.getOfficialSeller() }
        }
        SecurityContextHolder.clearContext()
        mvc.perform(put("/products/official/seller").contentType(MediaType.APPLICATION_JSON)
            .content("""{"displayName":"공식몰"}""")).andExpect(status().isForbidden)
        mvc.perform(put("/products/official/seller").header("X-User-Id", actor).header("X-User-Role", "ADMIN")
            .contentType(MediaType.APPLICATION_JSON).content("""{"displayName":"공식몰"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.id").value(SalesType.OFFICIAL_SELLER_ID.toString()))
        mvc.perform(get("/products/official/seller")).andExpect(status().isForbidden)
        mvc.perform(get("/products/official/seller").header("X-User-Id", actor).header("X-User-Role", "ADMIN")).andExpect(status().isOk).andExpect(jsonPath("$.displayName").value("공식몰"))
        assertEquals(1L, official.count())
    }

    @Test fun `표시 이름 변경과 중복은 판매 주체와 SKU를 바꾸지 않는다`() {
        login("ADMIN")
        val first = service.provisionOfficialSeller("공식몰")
        val direct = service.createOfficialProduct(request(), null)
        val normal = service.createProduct(request(10), UUID.randomUUID(), null)
        val renamed = service.provisionOfficialSeller("새 공식 이름")
        assertEquals(first.id, renamed.id); assertEquals(1L, official.count())
        assertEquals(normal.name, direct.name)
        assertEquals(SalesType.DIRECT_PURCHASE, direct.salesType)
        assertEquals(SalesType.MARKETPLACE, normal.salesType)
        assertNotEquals(normal.sellerId, direct.sellerId)
        assertEquals(direct.productId, direct.skuId)
        assertEquals(InventorySource.INVENTORY_SERVICE, direct.inventorySource)
        assertThrows(AccessDeniedException::class.java) { service.createProduct(request(), first.id, null) }
        val event = outbox.findAll().single { it.aggregateId == direct.productId }
        val payload = mapper.readValue(event.payload, ProductRegisteredPayload::class.java)
        assertEquals(2, payload.schemaVersion)
        assertEquals(direct.salesType, payload.salesType)
        assertEquals(direct.skuId, payload.skuId)
    }

    @Test fun `미발급 주체와 초기 수량 우회는 상품과 Outbox를 남기지 않는다`() {
        login("ADMIN")
        assertThrows(IllegalStateException::class.java) { service.createOfficialProduct(request(), null) }
        service.provisionOfficialSeller("공식몰")
        assertThrows(IllegalArgumentException::class.java) { service.createOfficialProduct(request(10), null) }
        assertEquals(0L, products.count()); assertEquals(0L, outbox.count())
    }

    @Test fun `일반 판매자의 공식 상품 수정 수량 변경과 Redis 동기화를 거부한다`() {
        login("ADMIN"); service.provisionOfficialSeller("공식몰")
        val direct = service.createOfficialProduct(request(), null)
        val update = UpdateProductRequest("변경", "설명", 20_000, ProductCategory.FLOWERS)
        login("SELLER")
        assertThrows(AccessDeniedException::class.java) { service.updateOfficialProduct(direct.productId, update, null) }
        for (id in listOf(actor, SalesType.OFFICIAL_SELLER_ID)) {
            assertThrows(ProductOwnershipException::class.java) { service.updateProduct(direct.productId, update, id, null) }
            assertThrows(ProductOwnershipException::class.java) { service.updateStock(direct.productId, 10, id) }
            assertThrows(ProductOwnershipException::class.java) { service.deactivateProduct(direct.productId, id) }
        }
        assertThrows(IllegalStateException::class.java) { service.applyInventoryStockAdjustment(
            InventoryStockAdjustedPayload(UUID.randomUUID(), direct.productId, UUID.randomUUID(), 10, "RESERVED"), java.time.Instant.now()) }
        assertThrows(IllegalStateException::class.java) { service.applyInventoryStockSnapshot(
            InventoryStockSnapshotPayload(direct.productId, 10), java.time.Instant.now()) }
        assertEquals(0, products.findById(direct.productId).orElseThrow().stock)
        assertEquals(1L, outbox.count())
    }

    @Test fun `관리자 직매입 수정 경로는 타인 입점 상품에 사용할 수 없다`() {
        login("ADMIN"); service.provisionOfficialSeller("공식몰")
        val normal = service.createProduct(request(10), UUID.randomUUID(), null)
        val update = UpdateProductRequest("변경", "설명", 20_000, ProductCategory.FLOWERS)
        assertThrows(ProductOwnershipException::class.java) { service.updateOfficialProduct(normal.productId, update, null) }
        assertThrows(ProductOwnershipException::class.java) { service.deactivateOfficialProduct(normal.productId) }
        val direct = service.createOfficialProduct(request(), null)
        val changed = service.updateOfficialProduct(direct.productId, update, null)
        assertEquals(direct.sellerId, changed.sellerId); assertEquals(direct.salesType, changed.salesType)
        assertEquals(direct.skuId, changed.skuId); assertEquals(20_000L, changed.price)
        val event = outbox.findAll().single { it.eventType == "PRODUCT_UPDATED" }
        assertEquals(SalesType.DIRECT_PURCHASE, mapper.readValue(event.payload, ProductUpdatedPayload::class.java).salesType)
    }

    @Test fun `실제 HTTP 상품 생성은 관리자 경로만 직매입을 발행한다`() {
        login("ADMIN"); service.provisionOfficialSeller("공식몰"); SecurityContextHolder.clearContext()
        val json = MockMultipartFile("request", "request.json", "application/json", mapper.writeValueAsBytes(request()))
        mvc.perform(multipart("/products/official").file(json).header("X-User-Id", actor).header("X-User-Role", "SELLER"))
            .andExpect(status().isForbidden)
        mvc.perform(multipart("/products/official").file(json).header("X-User-Id", actor).header("X-User-Role", "ADMIN"))
            .andExpect(status().isCreated).andExpect(jsonPath("$.salesType").value("DIRECT_PURCHASE"))
        assertEquals(1L, products.count())
    }

    @Test fun `상품 SQL migration은 공식 주체 판매 방식 직매입 수량을 강제한다`() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { s ->
                    s.execute("CREATE SCHEMA s1_product_contract")
                    s.execute("SET LOCAL search_path TO s1_product_contract")
                    s.execute("CREATE TABLE products (id UUID PRIMARY KEY, seller_id UUID NOT NULL, stock INT NOT NULL)")
                    s.execute(javaClass.classLoader.getResourceAsStream("db/migration/V2__sales_identity.sql")!!.bufferedReader().readText())
                    s.execute("INSERT INTO official_seller VALUES ('${SalesType.OFFICIAL_SELLER_ID}', '공식몰')")
                    s.execute("INSERT INTO products VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}', 10, 'MARKETPLACE')")
                    s.execute("INSERT INTO products VALUES ('${UUID.randomUUID()}', '${SalesType.OFFICIAL_SELLER_ID}', 0, 'DIRECT_PURCHASE')")
                    for (sql in listOf(
                        "INSERT INTO official_seller VALUES ('${UUID.randomUUID()}', '도용')",
                        "INSERT INTO products VALUES ('${UUID.randomUUID()}', '${SalesType.OFFICIAL_SELLER_ID}', 0, 'MARKETPLACE')",
                        "INSERT INTO products VALUES ('${UUID.randomUUID()}', '${UUID.randomUUID()}', 0, 'DIRECT_PURCHASE')",
                        "INSERT INTO products VALUES ('${UUID.randomUUID()}', '${SalesType.OFFICIAL_SELLER_ID}', 10, 'DIRECT_PURCHASE')")) {
                        val point = connection.setSavepoint()
                        assertThrows(SQLException::class.java) { s.execute(sql) }
                        connection.rollback(point)
                    }
                }
            } finally { connection.rollback() }
        }
    }
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @DynamicPropertySource @JvmStatic fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@Import(ProductPostgresTestConfig::class, SecurityConfig::class, OfficialProductController::class,
    ProductController::class, GlobalExceptionHandler::class)
class OfficialProductTestConfig
