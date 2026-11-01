package com.eventfulcommerce.product.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductStatus
import com.eventfulcommerce.product.domain.entity.Product
import com.eventfulcommerce.product.dto.CreateProductRequest
import com.eventfulcommerce.product.dto.UpdateProductRequest
import com.eventfulcommerce.product.repository.ProductRepository
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.springframework.mock.web.MockMultipartFile
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = [ProductPostgresTestConfig::class], webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.profiles.active=test", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"])
class ProductPostgresIntegrationTest {
    @Autowired lateinit var products: ProductRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var service: ProductService
    @Autowired lateinit var tm: PlatformTransactionManager
    @Autowired lateinit var em: EntityManager
    @Autowired lateinit var images: ImageStorageService
    @Autowired lateinit var directory: Path
    @Autowired lateinit var jdbc: JdbcTemplate
    @SpyBean lateinit var mapper: ObjectMapper

    @BeforeEach fun clean() { outbox.deleteAll(); products.deleteAll() }
    private fun fixture() = products.saveAndFlush(Product(UUID.randomUUID(), "기존", "설명", 10_000, 10, ProductCategory.FLOWERS))
    private fun request() = UpdateProductRequest("수정", "새 설명", 20_000, ProductCategory.FLOWERS)

    @Test fun `정보와 Outbox는 함께 커밋된다`() {
        val p = fixture()
        service.updateProduct(p.id, request(), p.sellerId, null)
        assertEquals(20_000L, products.findById(p.id).orElseThrow().price)
        val event = outbox.findAll().single()
        assertEquals("PRODUCT_UPDATED", event.eventType)
        assertEquals(1L, mapper.readValue(event.payload, ProductUpdatedPayload::class.java).informationVersion)
    }

    @Test fun `checked JSON 실패는 상품과 Outbox를 함께 롤백한다`() {
        val p = fixture()
        Mockito.doThrow(object : JsonProcessingException("강제 직렬화 실패") {}).`when`(mapper).writeValueAsString(Mockito.any())
        assertThrows(JsonProcessingException::class.java) { service.updateProduct(p.id, request(), p.sellerId, null) }
        assertEquals(10_000L, products.findById(p.id).orElseThrow().price)
        assertEquals(0L, products.findById(p.id).orElseThrow().informationVersion)
        assertEquals(0L, outbox.count())
    }

    @Test fun `수정 중 재고 갱신은 행 잠금을 기다리고 재고를 보존한다`() {
        val p = fixture()
        interleave({ service.updateProduct(p.id, request(), p.sellerId, null) },
            { service.updateStock(p.id, -1, p.sellerId) })
        val actual = products.findById(p.id).orElseThrow()
        assertEquals(9, actual.stock)
        assertEquals("수정", actual.name)
        assertEquals(2L, outbox.count())
    }

    @Test fun `재고 갱신 중 수정도 행 잠금을 기다린다`() {
        val p = fixture()
        interleave({ service.updateStock(p.id, -1, p.sellerId) },
            { service.updateProduct(p.id, request(), p.sellerId, null) })
        val actual = products.findById(p.id).orElseThrow()
        assertEquals(9, actual.stock)
        assertEquals(20_000L, actual.price)
    }

    @Test fun `비활성화 중 수정은 비활성 상태를 보존한다`() {
        val p = fixture()
        interleave({ service.deactivateProduct(p.id, p.sellerId) },
            { service.updateProduct(p.id, request(), p.sellerId, null) })
        assertEquals(ProductStatus.INACTIVE, products.findById(p.id).orElseThrow().status)
    }

    @Test fun `수정 중 비활성화도 행 잠금을 기다린다`() {
        val p = fixture()
        interleave({ service.updateProduct(p.id, request(), p.sellerId, null) },
            { service.deactivateProduct(p.id, p.sellerId) })
        val actual = products.findById(p.id).orElseThrow()
        assertEquals(ProductStatus.INACTIVE, actual.status)
        assertEquals(20_000L, actual.price)
    }

    private fun image(name: String) = MockMultipartFile("images", name, "image/png", byteArrayOf(1, 2, 3))
    private fun file(url: String) = directory.resolve(url.substringAfterLast('/')).toFile()
    private fun withImage(): Product {
        val p = fixture()
        val url = images.save(image("old.png"))
        TransactionTemplate(tm).executeWithoutResult { products.findByIdForUpdate(p.id)!!.imageUrls.add(url) }
        return products.findById(p.id).orElseThrow()
    }
    private fun brokenImage() = object : MockMultipartFile("images", "broken.png", "image/png", byteArrayOf(9)) {
        override fun transferTo(dest: File) { dest.writeBytes(byteArrayOf(9)); throw IOException("중간 업로드 실패") }
    }

    @Test fun `이미지 교체 성공은 DB 커밋 뒤에만 이전 파일을 정리한다`() {
        val p = withImage(); val previous = p.imageUrls.single()
        lateinit var uploaded: String
        TransactionTemplate(tm).executeWithoutResult {
            uploaded = service.updateProduct(p.id, request(), p.sellerId, listOf(image("new.png"))).imageUrls.single()
            assertTrue(file(previous).exists()); assertTrue(file(uploaded).exists())
        }
        assertFalse(file(previous).exists()); assertTrue(file(uploaded).exists())
        assertEquals(listOf(uploaded), products.findById(p.id).orElseThrow().imageUrls)
    }

    @Test fun `첫 파일과 중간 업로드 실패는 기존 이미지와 DB를 보존한다`() {
        val p = withImage(); val previous = p.imageUrls.toList()
        val filesBefore = directory.toFile().listFiles()!!.map { it.name }.toSet()
        listOf(listOf(brokenImage()), listOf(image("new.png"), brokenImage())).forEach { uploads ->
            assertThrows(IOException::class.java) { service.updateProduct(p.id, request(), p.sellerId, uploads) }
            assertEquals(previous, products.findById(p.id).orElseThrow().imageUrls)
            assertTrue(file(previous.single()).exists())
            assertEquals(filesBefore, directory.toFile().listFiles()!!.map { it.name }.toSet())
        }
    }

    @Test fun `신규 상품의 중간 업로드 실패도 DB와 신규 파일을 정리한다`() {
        val filesBefore = directory.toFile().listFiles()!!.map { it.name }.toSet()
        assertThrows(IOException::class.java) {
            service.createProduct(CreateProductRequest("신규", "설명", 10_000, 10, ProductCategory.FLOWERS),
                UUID.randomUUID(), listOf(image("new.png"), brokenImage()))
        }
        assertEquals(0L, products.count()); assertEquals(0L, outbox.count())
        assertEquals(filesBefore, directory.toFile().listFiles()!!.map { it.name }.toSet())
    }

    @Test fun `DB 커밋 실패도 기존 파일을 보존하고 신규 파일만 정리한다`() {
        val p = withImage(); val previous = p.imageUrls.toList()
        val filesBefore = directory.toFile().listFiles()!!.map { it.name }.toSet()
        jdbc.execute("CREATE FUNCTION reject_product_commit() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''강제 커밋 실패''; END'")
        jdbc.execute("CREATE CONSTRAINT TRIGGER reject_product_commit AFTER UPDATE ON products DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_product_commit()")
        try {
            assertThrows(RuntimeException::class.java) {
                service.updateProduct(p.id, request(), p.sellerId, listOf(image("new.png")))
            }
            assertEquals(previous, products.findById(p.id).orElseThrow().imageUrls)
            assertEquals(10_000L, products.findById(p.id).orElseThrow().price)
            assertTrue(file(previous.single()).exists())
            assertEquals(filesBefore, directory.toFile().listFiles()!!.map { it.name }.toSet())
            assertEquals(0L, outbox.count())
        } finally {
            jdbc.execute("DROP TRIGGER reject_product_commit ON products")
            jdbc.execute("DROP FUNCTION reject_product_commit()")
        }
    }

    private fun interleave(first: () -> Unit, second: () -> Unit) {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner = pool.submit { TransactionTemplate(tm).executeWithoutResult {
                first(); em.flush(); held.countDown(); check(release.await(10, TimeUnit.SECONDS))
            } }
            assertTrue(held.await(10, TimeUnit.SECONDS))
            val waiter = pool.submit { started.countDown(); second() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { waiter.get(250, TimeUnit.MILLISECONDS) }
            release.countDown()
            owner.get(10, TimeUnit.SECONDS); waiter.get(10, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
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
@EnableAutoConfiguration
@EntityScan(basePackageClasses = [Product::class, OutboxEvent::class])
@EnableJpaRepositories(basePackageClasses = [ProductRepository::class, OutboxEventRepository::class])
@EnableJpaAuditing
@Import(ProductService::class, OutboxEventService::class, OutboxRetryPolicy::class, OutboxPublisherProperties::class)
class ProductPostgresTestConfig {
    @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    @Bean fun imageDirectory(): Path = Files.createTempDirectory("product-images-test")
    @Bean fun images(directory: Path) = ImageStorageService(directory.toString(), "http://test/images")
}
