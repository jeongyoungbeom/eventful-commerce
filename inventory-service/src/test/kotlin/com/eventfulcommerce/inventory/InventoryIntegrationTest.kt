package com.eventfulcommerce.inventory

import com.eventfulcommerce.common.*
import com.eventfulcommerce.common.auth.*
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.inventory.domain.*
import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.message.ProductEventsConsumer
import com.eventfulcommerce.inventory.repository.*
import com.eventfulcommerce.inventory.service.InventoryService
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.*
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["server.address=127.0.0.1",
    "dlt.topics=false", "spring.profiles.active=test", "outbox.publisher.enabled=false",
    "spring.kafka.listener.auto-startup=false", "inventory.reservations.expiry-enabled=false", "inventory.service-token="])
class InventoryIntegrationTest {
    @org.springframework.boot.test.web.server.LocalServerPort var port: Int = 0
    @Autowired lateinit var service: InventoryService
    @Autowired lateinit var consumer: ProductEventsConsumer
    @Autowired lateinit var skus: InventorySkuRepository
    @Autowired lateinit var balances: StockBalanceRepository
    @Autowired lateinit var lots: InventoryLotRepository
    @Autowired lateinit var receipts: InboundReceiptRepository
    @Autowired lateinit var inspections: InspectionRepository
    @Autowired lateinit var ledger: StockLedgerRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var jwt: JwtTokenProvider
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @SpyBean lateinit var mapper: ObjectMapper
    private val actor = UUID.randomUUID()
    private val tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1)
    private fun login(role: String = "ADMIN") {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(actor, null,
            listOf(SimpleGrantedAuthority("ROLE_$role")))
    }
    @BeforeEach fun clean() {
        Mockito.reset(mapper)
        jdbc.execute("TRUNCATE inventory_warehouse, inventory_supplier, inventory_sku, outbox_event, processed_event CASCADE")
        login()
    }
    @AfterEach fun clear() { SecurityContextHolder.clearContext() }
    private fun event(id: UUID, type: String, payload: Any, eventId: UUID = UUID.randomUUID()): String =
        mapper.writeValueAsString(OutboxEventMessage(eventId, "PRODUCT", id, type, Instant.now(), mapper.writeValueAsString(payload)))
    private fun sku(id: UUID = UUID.randomUUID()): UUID {
        consumer.consume(event(id, "PRODUCT_REGISTERED", ProductRegisteredPayload(id, SalesType.OFFICIAL_SELLER_ID,
            "직매입", 10_000, 0, "TEST", SalesType.DIRECT_PURCHASE, id, 2)))
        return id
    }
    private data class Fixture(val sku: UUID, val warehouse: Warehouse, val supplier: Supplier, val order: PurchaseOrderResponse)
    private fun fixture(quantity: Long = 100, unitCost: Long = 100, skuId: UUID = sku(),
        warehouse: Warehouse = service.createWarehouse(WarehouseRequest(UUID.randomUUID().toString(), "중앙창고", "서울")),
        supplier: Supplier = service.createSupplier(SupplierRequest(UUID.randomUUID().toString(), "공급사"))): Fixture {
        val po = service.createPurchaseOrder(UUID.randomUUID(), PurchaseOrderRequest(supplier.id, warehouse.id,
            listOf(PurchaseLineRequest(skuId, quantity, unitCost))))
        return Fixture(skuId, warehouse, supplier, po)
    }
    private fun inbound(f: Fixture, quantity: Long = 95, code: String = "LOT-A", quality: LotQuality = LotQuality.GOOD,
        expires: LocalDate = tomorrow) = InboundRequest(f.order.order.id, "도착 검수", listOf(
            InboundLineRequest(f.order.lines.single().id, code, expires, quality, quantity)))
    private fun expectStatus(status: Int, action: () -> Unit) {
        assertEquals(status, assertThrows(ResponseStatusException::class.java, action).statusCode.value())
    }
    private fun counts() = listOf("inventory_receipt", "inventory_receipt_line", "inventory_stock_balance", "inventory_lot",
        "inventory_stock_ledger", "inventory_inspection", "outbox_event").associateWith { jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java) }
    private fun parallel(actions: List<() -> Any>): List<Any> {
        val pool = Executors.newFixedThreadPool(actions.size)
        val start = CountDownLatch(1)
        return try {
            val futures = actions.map { action -> pool.submit<Any> {
                login()
                try { assertTrue(start.await(5, TimeUnit.SECONDS)); action() } finally { SecurityContextHolder.clearContext() }
            } }
            start.countDown()
            futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    @Test fun `빈 PostgreSQL은 공통 스키마까지 마이그레이션하고 서비스와 Swagger가 기동한다`() {
        assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Int::class.java))
        val http = java.net.http.HttpClient.newHttpClient()
        val actual = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI("http://127.0.0.1:$port/actuator/health")).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString())
        assertEquals(200, actual.statusCode())
        assertEquals("UP", mapper.readTree(actual.body())["status"].asText())
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/inventory/receipts/{id}'].put").exists())
    }

    @Test fun `100 발주의 95 도착은 정상 불량 검수 중으로 구분하고 잔량 원가 원장을 보존한다`() {
        val f = fixture()
        assertTrue(service.stock(f.sku, null, 0, 50).isEmpty)
        val line = f.order.lines.single().id
        val request = InboundRequest(f.order.order.id, "95개 도착", listOf(
            InboundLineRequest(line, "SAME-LOT", tomorrow, LotQuality.GOOD, 90),
            InboundLineRequest(line, "SAME-LOT", tomorrow, LotQuality.DAMAGED, 3),
            InboundLineRequest(line, "SAME-LOT", tomorrow, LotQuality.INSPECTION, 2)))
        val receipt = service.receive(UUID.randomUUID(), request)
        val stock = service.stock(f.sku, f.warehouse.id, 0, 50).content
        assertEquals(95L, stock.sumOf { it.balance.onHand })
        assertEquals(90L, stock.sumOf { it.available })
        assertEquals(5L, stock.sumOf { it.balance.blocked })
        assertEquals(95L, ledger.findAll().sumOf { it.onHandDelta })
        assertEquals(5L, ledger.findAll().sumOf { it.blockedDelta })
        assertTrue(ledger.findAll().all { it.actorId == actor && it.receiptId == receipt.receipt.id && it.reason == "95개 도착" })
        assertEquals(9_500L, receipt.purchaseAmount)
        assertTrue(receipt.lines.all { it.line.unitCost == 100L && it.lot.supplierId == f.supplier.id })
        assertEquals("PARTIALLY_RECEIVED", service.purchaseOrder(f.order.order.id).status)
        assertEquals(95L, service.purchaseOrder(f.order.order.id).lines.single().receivedQuantity)
        val emitted = outbox.findAll().single()
        assertEquals("INVENTORY_RECEIVED", emitted.eventType)
        assertEquals(receipt.receipt.id, emitted.aggregateId)
        assertEquals(9_500L, mapper.readTree(emitted.payload)["purchaseAmount"].asLong())
    }

    @Test fun `같은 SKU 로트도 두 창고의 잔량은 독립적이다`() {
        val first = fixture()
        val second = fixture(20, skuId = first.sku, supplier = first.supplier,
            warehouse = service.createWarehouse(WarehouseRequest("URBAN", "도심창고", "서울")))
        service.receive(UUID.randomUUID(), inbound(first, 100))
        service.receive(UUID.randomUUID(), inbound(second, 20))
        assertEquals(100L, service.stock(first.sku, first.warehouse.id, 0, 50).content.single().available)
        assertEquals(20L, service.stock(first.sku, second.warehouse.id, 0, 50).content.single().available)
        assertEquals(1L, lots.count()); assertEquals(2L, balances.count())
        val summary = service.stockSummary(first.sku, null).associateBy { it.warehouseId }
        assertEquals(100L, summary.getValue(first.warehouse.id).available)
        assertEquals(20L, summary.getValue(second.warehouse.id).onHand)
        assertEquals(1, service.stockSummary(first.sku, second.warehouse.id).size)
    }

    @Test fun `발주와 입고 재시도는 ID 본문을 비교하고 줄 순서는 무시한다`() {
        val f = fixture()
        val poBody = PurchaseOrderRequest(f.supplier.id, f.warehouse.id, listOf(PurchaseLineRequest(f.sku, 100, 100)))
        assertEquals(f.order.order.id, service.createPurchaseOrder(f.order.order.id, poBody).order.id)
        expectStatus(409) { service.createPurchaseOrder(f.order.order.id, poBody.copy(lines = listOf(PurchaseLineRequest(f.sku, 101, 100)))) }
        val id = UUID.randomUUID()
        val body = inbound(f, 90).copy(lines = inbound(f, 90).lines + inbound(f, 5, quality = LotQuality.DAMAGED).lines)
        val first = service.receive(id, body)
        val second = service.receive(id, body.copy(lines = body.lines.reversed()))
        assertEquals(first.receipt.confirmedAt, second.receipt.confirmedAt)
        assertEquals(first.lines.map { it.line.id }, second.lines.map { it.line.id })
        expectStatus(409) { service.receive(id, body.copy(reason = "다른 요청")) }
        expectStatus(409) { service.receive(id, body.copy(lines = listOf(body.lines.first().copy(quantity = 91)))) }
        assertEquals(1L, receipts.count()); assertEquals(1L, outbox.count()); assertEquals(2L, ledger.count())
    }

    @Test fun `분할 입고는 남은 5개까지 허용하고 초과와 타 발주 품목은 거부한다`() {
        val f = fixture()
        service.receive(UUID.randomUUID(), inbound(f))
        val before = counts()
        expectStatus(409) { service.receive(UUID.randomUUID(), inbound(f, 6)) }
        assertEquals(before, counts())
        service.receive(UUID.randomUUID(), inbound(f, 5))
        assertEquals("RECEIVED", service.purchaseOrder(f.order.order.id).status)
        expectStatus(409) { service.receive(UUID.randomUUID(), inbound(f, 1)) }
        val other = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            service.receive(UUID.randomUUID(), inbound(other).copy(lines = inbound(f, 1).lines))
        }
        assertEquals(100L, service.stock(f.sku, null, 0, 50).content.single().available)
    }

    @Test fun `같은 로트 사용기한 변경은 롤백하고 만료 당일과 과거는 판매량에서 제외한다`() {
        val f = fixture()
        service.receive(UUID.randomUUID(), inbound(f, 10))
        expectStatus(409) { service.receive(UUID.randomUUID(), inbound(f, 10, expires = tomorrow.plusDays(1))) }
        service.receive(UUID.randomUUID(), inbound(f, 10, "EXPIRED", expires = tomorrow.minusDays(2)))
        service.receive(UUID.randomUUID(), inbound(f, 10, "TODAY", expires = tomorrow.minusDays(1)))
        assertEquals(30L, balances.findAll().sumOf { it.onHand })
        assertEquals(10L, service.stock(f.sku, null, 0, 50).content.sumOf { it.available })
        assertThrows(IllegalArgumentException::class.java) {
            service.receive(UUID.randomUUID(), inbound(f, 1, "BAD", expires = LocalDate.of(10000,1,1)))
        }
    }

    @Test fun `직렬화 checked 예외는 문서 발주 로트 잔량 원장 Outbox를 함께 롤백한다`() {
        val f = fixture()
        service.receive(UUID.randomUUID(), inbound(f, 10))
        val before = counts()
        Mockito.doAnswer { invocation ->
            if (invocation.arguments[0] is Map<*, *>) throw object : JsonProcessingException("강제 직렬화 실패") {}
            invocation.callRealMethod()
        }.`when`(mapper).writeValueAsString(ArgumentMatchers.any())
        assertThrows(JsonProcessingException::class.java) { service.receive(UUID.randomUUID(), inbound(f, 5, "NEW")) }
        assertEquals(before, counts())
        assertEquals(10L, balances.findAll().single().onHand)
        assertEquals(10L, service.purchaseOrder(f.order.order.id).lines.single().receivedQuantity)
    }

    @Test fun `Outbox DB 제약 실패도 기존 잔량과 문서 전체를 롤백한다`() {
        val f = fixture()
        service.receive(UUID.randomUUID(), inbound(f, 10))
        val before = counts()
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT test_fail_outbox CHECK (aggregate_type <> 'INVENTORY_RECEIPT') NOT VALID")
        try {
            assertThrows(Exception::class.java) { service.receive(UUID.randomUUID(), inbound(f, 5)) }
        } finally { jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT test_fail_outbox") }
        assertEquals(before, counts())
        assertEquals(10L, balances.findAll().single().onHand)
        assertEquals(10L, service.purchaseOrder(f.order.order.id).lines.single().receivedQuantity)
    }

    @Test fun `같은 입고 ID 여섯 동시 요청은 한 번만 반영한다`() {
        val f = fixture()
        val id = UUID.randomUUID(); val body = inbound(f)
        val results = parallel(List(6) { { service.receive(id, body) } })
        assertEquals(6, results.size)
        assertEquals(95L, balances.findAll().single().onHand)
        assertEquals(1L, receipts.count()); assertEquals(1L, ledger.count()); assertEquals(1L, outbox.count())
    }

    @Test fun `서로 다른 입고의 발주 잔량 경합은 초과를 거부한다`() {
        val f = fixture()
        val outcomes = parallel(List(2) { { try { service.receive(UUID.randomUUID(), inbound(f, 60)); 200 }
            catch (ex: ResponseStatusException) { ex.statusCode.value() } } })
        assertEquals(listOf(200,409), outcomes.map { it as Int }.sorted())
        assertEquals(60L, balances.findAll().single().onHand)
        assertEquals(60L, service.purchaseOrder(f.order.order.id).lines.single().receivedQuantity)
    }

    @Test fun `서로 다른 발주의 같은 SKU 로트 부재 경합은 잔량을 합친다`() {
        val a = fixture()
        val b = fixture(skuId = a.sku, warehouse = a.warehouse, supplier = a.supplier)
        parallel(listOf({ service.receive(UUID.randomUUID(), inbound(a, 20)) }, { service.receive(UUID.randomUUID(), inbound(b, 30)) }))
        assertEquals(1L, lots.count()); assertEquals(1L, balances.count())
        assertEquals(50L, balances.findAll().single().onHand)
        assertEquals(2L, receipts.count()); assertEquals(2L, ledger.count()); assertEquals(2L, outbox.count())
    }

    @Test fun `수량 금액과 합산 오버플로는 아무 효과 없이 거부한다`() {
        val f = fixture(Long.MAX_VALUE, 1)
        assertThrows(ArithmeticException::class.java) { fixture(Long.MAX_VALUE, 2) }
        service.receive(UUID.randomUUID(), inbound(f, Long.MAX_VALUE))
        val next = fixture(1, 1, f.sku, f.warehouse, f.supplier)
        val before = counts()
        assertThrows(ArithmeticException::class.java) { service.receive(UUID.randomUUID(), inbound(next, 1)) }
        assertEquals(before, counts())
        assertEquals(Long.MAX_VALUE, balances.findAll().single().onHand)
        assertEquals(0L, service.purchaseOrder(next.order.order.id).lines.single().receivedQuantity)
    }

    @Test fun `DB는 고아 SKU 로트 중복과 수량 불변조건을 강제한다`() {
        val f = fixture(); service.receive(UUID.randomUUID(), inbound(f, 10))
        val balance = balances.findAll().single()
        listOf(
            "UPDATE inventory_stock_balance SET on_hand = -1 WHERE id = '${balance.id}'",
            "UPDATE inventory_stock_balance SET reserved = 11 WHERE id = '${balance.id}'",
            "UPDATE inventory_stock_balance SET reserved = 9223372036854775807, allocated = 9223372036854775807 WHERE id = '${balance.id}'",
            "UPDATE inventory_stock_balance SET sku_id = '${UUID.randomUUID()}' WHERE id = '${balance.id}'",
            "UPDATE inventory_purchase_line SET received_quantity = 101 WHERE id = '${f.order.lines.single().id}'",
            "INSERT INTO inventory_stock_balance SELECT '${UUID.randomUUID()}', sku_id, warehouse_id, lot_id, on_hand, reserved, allocated, blocked, version FROM inventory_stock_balance"
        ).forEach { sql -> assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) { jdbc.execute(sql) } }
        assertEquals(10L, balances.findAll().single().onHand)
    }

    @Test fun `상품 이벤트는 직매입만 투영하며 역순 버전 중복 비활성 경계를 유지한다`() {
        val market = UUID.randomUUID()
        consumer.consume(event(market, "PRODUCT_REGISTERED", ProductRegisteredPayload(market, actor, "입점", 100, 99, "TEST")))
        assertFalse(skus.existsById(market))
        val id = sku()
        val update = ProductUpdatedPayload(id, SalesType.OFFICIAL_SELLER_ID, "변경", 200, "TEST", 2,
            SalesType.DIRECT_PURCHASE, id, 2)
        consumer.consume(event(id, "PRODUCT_UPDATED", update))
        consumer.consume(event(id, "PRODUCT_UPDATED", update.copy(name = "오래됨", informationVersion = 1)))
        consumer.consume(event(id, "PRODUCT_DEACTIVATED", ProductDeactivatedPayload(id, SalesType.OFFICIAL_SELLER_ID)))
        sku(id)
        assertEquals("변경", skus.findById(id).orElseThrow().name)
        assertFalse(skus.findById(id).orElseThrow().active)
        assertEquals(0L, balances.count()); assertEquals(0L, outbox.count())
        expectStatus(409) { fixture(skuId = id) }
        expectStatus(404) { fixture(skuId = market) }
    }

    @Test fun `이벤트 잘못된 aggregate와 선행 누락은 marker까지 롤백하고 재처리가 가능하다`() {
        val id = UUID.randomUUID(); val eventId = UUID.randomUUID()
        val payload = ProductDeactivatedPayload(id, SalesType.OFFICIAL_SELLER_ID)
        assertThrows(IllegalStateException::class.java) { consumer.consume(event(id, "PRODUCT_DEACTIVATED", payload, eventId)) }
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM processed_event", Long::class.java))
        sku(id)
        consumer.consume(event(id, "PRODUCT_DEACTIVATED", payload, eventId))
        consumer.consume(event(id, "PRODUCT_DEACTIVATED", payload, eventId))
        assertFalse(skus.findById(id).orElseThrow().active)
        val before = jdbc.queryForObject("SELECT count(*) FROM processed_event", Long::class.java)
        assertThrows(IllegalArgumentException::class.java) { consumer.consume(event(UUID.randomUUID(), "PRODUCT_DEACTIVATED", payload)) }
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM processed_event", Long::class.java))
    }

    @Test fun `직접 관리자 API는 access JWT만 받고 역할 헤더 refresh 만료 서명 위조를 거부한다`() {
        SecurityContextHolder.clearContext()
        mvc.perform(get("/inventory/warehouses").header("X-User-Id", actor).header("X-User-Role", "ADMIN"))
            .andExpect(status().isUnauthorized)
        for (role in listOf(UserRole.USER, UserRole.SELLER)) {
            mvc.perform(get("/inventory/warehouses").header("Authorization", "Bearer " + jwt.createAccessToken(actor, role))
                .header("X-User-Role", "ADMIN")).andExpect(status().isForbidden)
        }
        val badKey = JwtTokenProvider(JwtConfig(secret = UUID.randomUUID().toString().repeat(2)))
        val expired = JwtTokenProvider(JwtConfig(secret = testSecret, accessTokenValidity = -1000))
        for (token in listOf(jwt.createRefreshToken(actor, UserRole.ADMIN), badKey.createAccessToken(actor, UserRole.ADMIN),
            expired.createAccessToken(actor, UserRole.ADMIN), "invalid")) {
            mvc.perform(get("/inventory/warehouses").header("Authorization", "Bearer $token")).andExpect(status().isUnauthorized)
        }
        mvc.perform(get("/inventory/warehouses").header("Authorization", "Bearer " + jwt.createAccessToken(actor, UserRole.ADMIN))
            .header("X-User-Role", "USER")).andExpect(status().isOk)
        mvc.perform(put("/internal/reservations/" + UUID.randomUUID()).header("Authorization", "Bearer " + jwt.createAccessToken(actor, UserRole.ADMIN)))
            .andExpect(status().isForbidden)
        login("SELLER")
        assertThrows(AccessDeniedException::class.java) { service.createWarehouse(WarehouseRequest("BAD", "창고", "서울")) }
    }

    @Test fun `실제 HTTP 발주 입고는 서버 단가 작업자를 사용하고 잘못된 입력은 400이다`() {
        val f = fixture()
        SecurityContextHolder.clearContext()
        val token = jwt.createAccessToken(actor, UserRole.ADMIN)
        val id = UUID.randomUUID()
        mvc.perform(put("/inventory/receipts/$id").header("Authorization", "Bearer $token")
            .header("X-User-Id", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(inbound(f))))
            .andExpect(status().isOk).andExpect(jsonPath("$.purchaseAmount").value(9500))
            .andExpect(jsonPath("$.receipt.actorId").value(actor.toString()))
        mvc.perform(get("/inventory/receipts/$id").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andExpect(jsonPath("$.lines[0].line.unitCost").value(100))
        for (quantity in listOf(0L, -1L)) {
            mvc.perform(put("/inventory/receipts/" + UUID.randomUUID()).header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(inbound(f, quantity))))
                .andExpect(status().isBadRequest)
        }
        mvc.perform(get("/inventory/stock").param("skuId", f.sku.toString()).param("size", "201")
            .header("Authorization", "Bearer $token")).andExpect(status().isBadRequest)
        mvc.perform(post("/inventory/warehouses").header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content("""{"code":" ","name":"창고","address":"서울"}"""))
            .andExpect(status().isBadRequest)
    }

    @Test fun `검수 확정은 총량 원가를 보존하고 정상 수량만 판매 가능하게 한다`() {
        val f = fixture(10)
        val receipt = service.receive(UUID.randomUUID(), inbound(f, 10, quality = LotQuality.INSPECTION))
        val id = UUID.randomUUID()
        val body = InspectionRequest(receipt.lines.single().line.id, 8, 2, "검수 완료")
        assertEquals(0L, service.stock(f.sku, null, 0, 50).content.sumOf { it.available })
        val decision = service.inspect(id, body)
        assertEquals(decision.id, service.inspect(id, body).id)
        val stock = service.stock(f.sku, null, 0, 50).content
        assertEquals(10L, stock.sumOf { it.balance.onHand })
        assertEquals(8L, stock.sumOf { it.available })
        assertEquals(2L, stock.sumOf { it.balance.blocked })
        val summary = service.stockSummary(f.sku, f.warehouse.id).single()
        assertEquals(10L, summary.onHand); assertEquals(2L, summary.blocked); assertEquals(8L, summary.available)
        assertEquals(1L, receipts.count()); assertEquals(1L, inspections.count()); assertEquals(2L, outbox.count())
        assertEquals(10L, service.purchaseOrder(f.order.order.id).lines.single().receivedQuantity)
        assertEquals(1_000L, service.receipt(receipt.receipt.id).purchaseAmount)
        assertEquals(10L, ledger.findAll().sumOf { it.onHandDelta })
        assertEquals(2L, ledger.findAll().sumOf { it.blockedDelta })
        assertEquals(0L, ledger.findAll().filter { it.documentId == id }.sumOf { it.onHandDelta })
        expectStatus(409) { service.inspect(id, body.copy(goodQuantity = 9, damagedQuantity = 1)) }
        expectStatus(409) { service.inspect(UUID.randomUUID(), body) }
        assertEquals("INVENTORY_INSPECTED", outbox.findAll().single { it.aggregateId == id }.eventType)
    }

    @Test fun `검수 Outbox 실패는 재분류 잔량 원장 문서를 모두 롤백한다`() {
        val f = fixture(10)
        val receipt = service.receive(UUID.randomUUID(), inbound(f, 10, quality = LotQuality.INSPECTION))
        val before = counts()
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT test_fail_inspection CHECK (aggregate_type <> 'INVENTORY_INSPECTION') NOT VALID")
        val id = UUID.randomUUID(); val body = InspectionRequest(receipt.lines.single().line.id, 8, 2, "검수")
        try { assertThrows(Exception::class.java) { service.inspect(id, body) } }
        finally { jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT test_fail_inspection") }
        assertEquals(before, counts())
        assertEquals(10L, balances.findAll().single().blocked)
        assertEquals(0L, service.stock(f.sku, null, 0, 50).content.single().available)
        service.inspect(id, body)
        assertEquals(8L, service.stock(f.sku, null, 0, 50).content.sumOf { it.available })
    }

    @Test fun `검수의 동시 결정은 같은 ID 중복과 다른 ID 재검수를 막는다`() {
        val f = fixture(10)
        val receipt = service.receive(UUID.randomUUID(), inbound(f, 10, quality = LotQuality.INSPECTION))
        val id = UUID.randomUUID(); val body = InspectionRequest(receipt.lines.single().line.id, 10, 0, "검수")
        parallel(List(4) { { service.inspect(id, body) } })
        assertEquals(1L, inspections.count()); assertEquals(10L, balances.findAll().sumOf { it.onHand })
        expectStatus(409) { service.inspect(UUID.randomUUID(), body) }
        assertEquals(2L, outbox.count())
    }

    @Test fun `검수 수량 불일치 정상 로트 재검수와 음수는 거부한다`() {
        val f = fixture()
        val pending = service.receive(UUID.randomUUID(), inbound(f, 10, quality = LotQuality.INSPECTION))
        assertThrows(IllegalArgumentException::class.java) {
            service.inspect(UUID.randomUUID(), InspectionRequest(pending.lines.single().line.id, 9, 0, "누락"))
        }
        val good = service.receive(UUID.randomUUID(), inbound(f, 10))
        assertThrows(IllegalArgumentException::class.java) {
            service.inspect(UUID.randomUUID(), InspectionRequest(good.lines.single().line.id, 10, 0, "재검수"))
        }
        SecurityContextHolder.clearContext()
        mvc.perform(put("/inventory/inspections/" + UUID.randomUUID()).header("Authorization", "Bearer " + jwt.createAccessToken(actor, UserRole.ADMIN))
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                InspectionRequest(pending.lines.single().line.id, -1, 11, "음수")))).andExpect(status().isBadRequest)
        assertEquals(0L, inspections.count()); assertEquals(2L, outbox.count())
    }

    @Test fun `입고 후 비활성 상품과 예약 차단 창고는 판매 가능량에서 제외한다`() {
        val f = fixture()
        service.receive(UUID.randomUUID(), inbound(f, 10))
        consumer.consume(event(f.sku, "PRODUCT_DEACTIVATED", ProductDeactivatedPayload(f.sku, SalesType.OFFICIAL_SELLER_ID)))
        assertEquals(0L, service.stock(f.sku, null, 0, 50).content.single().available)
        expectStatus(409) { service.receive(UUID.randomUUID(), inbound(f, 5)) }
        val closed = service.createWarehouse(WarehouseRequest("CLOSED", "예약 중지", "서울", acceptsReservations = false))
        val g = fixture(10, warehouse = closed)
        service.receive(UUID.randomUUID(), inbound(g, 10))
        assertEquals(10L, balances.findAll().single { it.skuId == g.sku }.onHand)
        assertEquals(0L, service.stock(g.sku, closed.id, 0, 50).content.single().available)
        val inactive = service.createWarehouse(WarehouseRequest("INACTIVE", "비활성", "서울", active = false))
        expectStatus(409) { fixture(warehouse = inactive) }
    }

    @Test fun `창고 합산도 Long 범위를 넘으면 잘라내지 않고 거부한다`() {
        val first = fixture(Long.MAX_VALUE, 1)
        val second = fixture(1, 1, first.sku, first.warehouse, first.supplier)
        service.receive(UUID.randomUUID(), inbound(first, Long.MAX_VALUE, "MAX"))
        service.receive(UUID.randomUUID(), inbound(second, 1, "EXTRA"))
        assertThrows(ArithmeticException::class.java) { service.stockSummary(first.sku, null) }
    }

    companion object {
        private val testSecret = UUID.randomUUID().toString().repeat(2)
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @DynamicPropertySource @JvmStatic fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("jwt.secret") { testSecret }
        }
    }
}
