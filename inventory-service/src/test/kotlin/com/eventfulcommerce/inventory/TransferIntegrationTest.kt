package com.eventfulcommerce.inventory

import com.eventfulcommerce.common.auth.*
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.inventory.domain.*
import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.repository.*
import com.eventfulcommerce.inventory.service.*
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.*
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

@TestConfiguration
class TransferTestClockConfiguration {
    @Bean @Primary fun transferClock(): Clock = TransferIntegrationTest.clock
}

@Testcontainers
@Import(TransferTestClockConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["server.address=127.0.0.1",
    "spring.profiles.active=test", "dlt.topics=false", "outbox.publisher.enabled=false",
    "spring.kafka.listener.auto-startup=false", "inventory.reservations.expiry-enabled=false"])
class TransferIntegrationTest {
    @Autowired lateinit var service: TransferService
    @Autowired lateinit var inventory: InventoryService
    @Autowired lateinit var reservation: ReservationService
    @Autowired lateinit var skus: InventorySkuRepository
    @Autowired lateinit var balances: StockBalanceRepository
    @Autowired lateinit var lots: InventoryLotRepository
    @Autowired lateinit var ledger: StockLedgerRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @SpyBean lateinit var mapper: ObjectMapper
    @LocalServerPort var port: Int = 0
    private val actor = UUID.randomUUID()
    private val action = TransferActionRequest("이동 작업 확인")
    private fun login(authority: String = "ROLE_ADMIN") {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(actor, null,
            listOf(SimpleGrantedAuthority(authority)))
    }
    @BeforeEach fun clean() {
        Mockito.reset(mapper)
        clock.time.set(Instant.now())
        jdbc.execute("TRUNCATE inventory_warehouse, inventory_supplier, inventory_sku, outbox_event, processed_event CASCADE")
        login()
    }
    @AfterEach fun clear() { SecurityContextHolder.clearContext() }
    private data class Fixture(val sku: UUID, val source: UUID, val destination: UUID, val receipt: ReceiptResponse) {
        val lot get() = receipt.lines.first().lot.id
    }
    private fun fixture(quantity: Long = 10, source: UUID? = null, destination: UUID? = null,
        quality: LotQuality = LotQuality.GOOD, expires: LocalDate = LocalDate.now(clock).plusDays(2)): Fixture {
        val sku = UUID.randomUUID()
        skus.saveAndFlush(InventorySku(sku, "이동 상품"))
        val from = source ?: inventory.createWarehouse(WarehouseRequest(UUID.randomUUID().toString(), "중앙", "서울")).id
        val to = destination ?: inventory.createWarehouse(WarehouseRequest(UUID.randomUUID().toString(), "도심", "서울", priority = 1)).id
        val supplier = inventory.createSupplier(SupplierRequest(UUID.randomUUID().toString(), "공급사"))
        val po = inventory.createPurchaseOrder(UUID.randomUUID(), PurchaseOrderRequest(supplier.id, from,
            listOf(PurchaseLineRequest(sku, quantity, 123))))
        val receipt = inventory.receive(UUID.randomUUID(), InboundRequest(po.order.id, "이동용 입고", listOf(
            InboundLineRequest(po.lines.single().id, "LOT", expires, quality, quantity))))
        return Fixture(sku, from, to, receipt)
    }
    private fun body(f: Fixture, quantity: Long = 6) = TransferRequest(f.source, f.destination,
        clock.instant().plusSeconds(3600), "창고 보충", listOf(TransferQuantity(f.lot, quantity)))
    private fun arrival(f: Fixture, quantity: Long) = TransferArrivalRequest("실물 도착 확인", listOf(TransferQuantity(f.lot, quantity)))
    private fun expectStatus(expected: Int, action: () -> Any) {
        assertEquals(expected, assertThrows(ResponseStatusException::class.java) { action() }.statusCode.value())
    }
    private fun snapshot(): Map<String, List<Map<String, Any>>> = listOf("inventory_transfer", "inventory_transfer_line",
        "inventory_transfer_receipt", "inventory_transfer_receipt_line", "inventory_stock_balance", "inventory_stock_ledger", "outbox_event")
        .associateWith { jdbc.queryForList("SELECT * FROM $it ORDER BY id") }
    private fun consistent() {
        val movements = ledger.findAll()
        for (b in balances.findAll()) {
            val rows = movements.filter { it.warehouseId == b.warehouseId && it.lotId == b.lotId }
            assertEquals(b.onHand, rows.sumOf { it.onHandDelta })
            assertEquals(b.reserved, rows.sumOf { it.reservedDelta })
            assertEquals(b.allocated, rows.sumOf { it.allocatedDelta })
            assertEquals(b.blocked, rows.sumOf { it.blockedDelta })
            assertEquals(b.transferHeld, rows.sumOf { it.transferHeldDelta })
            assertEquals(b.transferHeld, jdbc.queryForObject("""SELECT COALESCE(sum(l.quantity),0)::bigint
                FROM inventory_transfer_line l JOIN inventory_transfer t ON t.id=l.transfer_id
                WHERE t.status='HELD' AND l.source_warehouse_id=? AND l.lot_id=?""", Long::class.java, b.warehouseId, b.lotId))
            assertTrue(b.onHand >= b.reserved + b.allocated + b.blocked + b.transferHeld)
        }
    }
    private fun parallel(actions: List<() -> Any>): List<Any> {
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(actions.size)
        return try {
            val futures = actions.map { task -> pool.submit<Any> {
                login()
                try { assertTrue(start.await(5, TimeUnit.SECONDS)); task() } finally { SecurityContextHolder.clearContext() }
            } }
            start.countDown()
            futures.map { it.get(25, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
    private fun attempt(action: () -> Any): Any = try { action() } catch (e: ResponseStatusException) {
        assertEquals(409, e.statusCode.value()); "CONFLICT"
    }
    private fun http(method: String, path: String, body: Any? = null, expected: Int = 200, role: UserRole? = UserRole.ADMIN,
        bearer: String? = null): JsonNode {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/inventory/transfers$path")).timeout(Duration.ofSeconds(10))
        val token = bearer ?: role?.let { jwt.createAccessToken(actor, it) }
        if (token != null) request.header("Authorization", "Bearer $token")
        request.header("Content-Type", "application/json").method(method,
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        val result = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(expected, result.statusCode(), result.body())
        return if (result.body().isBlank()) mapper.createObjectNode() else mapper.readTree(result.body())
    }

    @Test fun `실제 HTTP 이동 확보 출발 부분 도착은 보존식과 로트 원가 참조를 유지한다`() {
        val f = fixture(); val id = UUID.randomUUID(); val request = body(f)
        http("PUT", "/$id", request)
        assertEquals(6L, inventory.stockSummary(f.sku, f.source).single().transferHeld)
        assertEquals(4L, inventory.stock(f.sku, f.source, 0, 10).content.single().available)
        assertEquals(4L, inventory.stockSummary(f.sku, f.source).single().available)
        login("INVENTORY_SERVICE")
        val rejected = reservation.reserve(UUID.randomUUID(), ReservationRequest(UUID.randomUUID(), UUID.randomUUID(), f.sku, 5, clock.instant().plusSeconds(600)))
        assertEquals(ReservationStatus.REJECTED, rejected.reservation.status)
        login()
        http("PUT", "/$id/dispatch", action); http("PUT", "/$id/dispatch", action)
        expectStatus(409) { service.dispatch(id, action.copy(reason = "다른 출발")) }
        fun total() = balances.findAll().sumOf { it.onHand } + service.get(id).lines.sumOf { it.inTransitQuantity }
        assertEquals(10L, total()); assertEquals(4L, balances.findAll().sumOf { it.onHand })
        http("PUT", "/$id/cancellation", action, 409)
        val receiptId = UUID.randomUUID()
        http("PUT", "/$id/receipts/$receiptId", arrival(f, 2))
        http("PUT", "/$id/receipts/$receiptId", arrival(f, 2))
        http("PUT", "/$id/receipts/$receiptId", arrival(f, 3), 409)
        assertEquals(TransferStatus.PARTIALLY_RECEIVED, service.get(id).transfer.status)
        assertEquals(4L, service.get(id).lines.single().inTransitQuantity); assertEquals(10L, total())
        http("PUT", "/$id/receipts/${UUID.randomUUID()}", arrival(f, 5), 409)
        http("PUT", "/$id/receipts/${UUID.randomUUID()}", arrival(f, 4))
        assertEquals(TransferStatus.RECEIVED, service.get(id).transfer.status)
        assertEquals(10L, total()); assertEquals(6L, inventory.stockSummary(f.sku, f.destination).single().available)
        assertEquals(2L, http("GET", "/$id/receipts")["totalElements"].asLong())
        assertEquals(2L, http("GET", "/$id/receipts/$receiptId")["lines"][0]["quantity"].asLong())
        val destination = inventory.stock(f.sku, f.destination, 0, 10).content.single()
        assertEquals(f.receipt.lines.single().lot.id, destination.lot.id)
        assertEquals(f.receipt.lines.single().lot.expiresOn, destination.lot.expiresOn)
        assertEquals(f.receipt.lines.single().lot.supplierId, destination.lot.supplierId)
        assertEquals(1230L, inventory.receipt(f.receipt.receipt.id).purchaseAmount)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM inventory_receipt_line", Long::class.java))
        assertTrue(outbox.findAll().filter { it.aggregateType == "INVENTORY_TRANSFER" }.all {
            mapper.readTree(it.payload)["schemaVersion"].asInt() == 1 })
        consistent()
    }

    @Test fun `마지막 재고의 고객 예약과 이동 확보 경쟁은 초과 확보하지 않는다`() {
        val f = fixture()
        val results = parallel((0 until 12).map { index -> {
            if (index % 2 == 0) attempt { service.create(UUID.randomUUID(), body(f, 2)) }
            else {
                login("INVENTORY_SERVICE")
                reservation.reserve(UUID.randomUUID(), ReservationRequest(UUID.randomUUID(), UUID.randomUUID(), f.sku, 2, clock.instant().plusSeconds(600)))
            }
        } })
        val success = results.count { it is TransferResponse || (it is ReservationResponse && it.reservation.status == ReservationStatus.RESERVED) }
        assertEquals(5, success)
        val balance = balances.findAll().single()
        assertEquals(10L, balance.transferHeld + balance.reserved)
        assertEquals(0L, inventory.stockSummary(f.sku, f.source).single().available); consistent()
    }

    @Test fun `같은 이동 ID 여섯 동시 재전송과 다른 내용은 최초 확보만 보존한다`() {
        val f = fixture(); val id = UUID.randomUUID(); val request = body(f)
        val results = parallel(List(6) { { service.create(id, request) } })
        assertTrue(results.all { (it as TransferResponse).transfer.id == id })
        val before = snapshot()
        expectStatus(409) { service.create(id, request.copy(lines = listOf(TransferQuantity(f.lot, 7)))) }
        assertEquals(before, snapshot()); assertEquals(6L, balances.findAll().single().transferHeld)
        assertEquals(1, outbox.findAll().count { it.eventType == "TRANSFER_HELD" }); consistent()
    }

    @Test fun `출발 전 취소는 확보만 해제하고 재생성 도착 출발로 되살리지 않는다`() {
        val f = fixture(); val id = UUID.randomUUID(); val request = body(f)
        service.create(id, request)
        expectStatus(409) { service.arrive(id, UUID.randomUUID(), arrival(f, 1)) }
        service.cancel(id, action); service.cancel(id, action)
        clock.time.set(request.expectedArrivalAt.plusSeconds(1))
        assertEquals(TransferStatus.CANCELED, service.create(id, request).transfer.status)
        expectStatus(409) { service.cancel(id, action.copy(reason = "다른 취소")) }
        expectStatus(409) { service.dispatch(id, action) }
        expectStatus(409) { service.arrive(id, UUID.randomUUID(), arrival(f, 1)) }
        assertEquals(0L, balances.findAll().single().transferHeld); assertEquals(10L, balances.findAll().single().onHand)
        assertEquals(0L, service.list(null, true, true, 0, 10).totalElements); consistent()
    }

    @Test fun `출발 취소 경합은 단일 종결 효과와 보유 이동 중 보존식을 지킨다`() {
        repeat(4) {
            val f = fixture(); val id = UUID.randomUUID()
            service.create(id, body(f))
            parallel(listOf({ attempt { service.dispatch(id, action) } }, { attempt { service.cancel(id, action) } }))
            val result = service.get(id); val b = balances.findAll().single { it.skuId == f.sku }
            assertEquals(0L, b.transferHeld)
            assertEquals(10L, b.onHand + result.lines.sumOf { it.inTransitQuantity })
            assertEquals(1, outbox.findAll().count { it.aggregateId == id && it.eventType in listOf("TRANSFER_DISPATCHED", "TRANSFER_CANCELED") })
        }
        consistent()
    }

    @Test fun `부분 도착 중복과 서로 다른 도착 경쟁은 잔여 수량만 반영한다`() {
        val f = fixture(); val id = UUID.randomUUID(); val receiptId = UUID.randomUUID()
        service.create(id, body(f, 10)); service.dispatch(id, action)
        parallel(List(6) { { service.arrive(id, receiptId, arrival(f, 3)) } })
        val results = parallel(List(2) { { attempt { service.arrive(id, UUID.randomUUID(), arrival(f, 5)) } } })
        assertEquals(1, results.count { it == "CONFLICT" })
        assertEquals(2L, service.get(id).lines.single().inTransitQuantity)
        service.arrive(id, UUID.randomUUID(), arrival(f, 2))
        assertEquals(10L, balances.findAll().sumOf { it.onHand }); assertEquals(3L, service.receipts(id, 0, 10).totalElements)
        consistent()
    }

    @Test fun `다품목 다로트 이동의 역순 요청과 동시 도착은 SKU 순서 잠금으로 합쳐진다`() {
        val a = fixture(10); val b = fixture(10, a.source, a.destination)
        val request = body(a, 2).copy(lines = listOf(TransferQuantity(a.lot, 2), TransferQuantity(b.lot, 3)))
        val ids = List(2) { UUID.randomUUID() }
        parallel(ids.map { id -> { service.create(id, request.copy(lines = request.lines.reversed())) } })
        assertEquals(ids[0], service.create(ids[0], request).transfer.id)
        parallel(ids.map { id -> { service.dispatch(id, action) } })
        parallel(ids.map { id -> { service.arrive(id, UUID.randomUUID(), TransferArrivalRequest("전량", request.lines)) } })
        assertEquals(4L, inventory.stockSummary(a.sku, a.destination).single().onHand)
        assertEquals(6L, inventory.stockSummary(b.sku, b.destination).single().onHand)
        consistent()
    }

    @Test fun `같은 도착 ID의 다른 이동 동시 확정은 원본을 덮어쓰지 않는다`() {
        val f = fixture(20); val ids = List(2) { UUID.randomUUID() }; val receiptId = UUID.randomUUID()
        ids.forEach { service.create(it, body(f)); service.dispatch(it, action) }
        val results = parallel(ids.map { id -> { attempt { service.arrive(id, receiptId, arrival(f, 3)) } } })
        assertEquals(1, results.count { it == "CONFLICT" })
        val winner = (results.first { it is TransferReceiptResponse } as TransferReceiptResponse).receipt.transferId
        assertEquals(winner, service.receipt(winner, receiptId).receipt.transferId)
        expectStatus(404) { service.receipt(ids.first { it != winner }, receiptId) }
        assertEquals(3L, inventory.stockSummary(f.sku, f.destination).single().onHand); consistent()
    }

    @ParameterizedTest @ValueSource(strings = ["create", "dispatch", "cancel", "arrive"])
    fun `Outbox DB 실패는 이동 문서 잔량 원장 도착을 함께 롤백한다`(phase: String) {
        rollback(phase, false)
    }
    @ParameterizedTest @ValueSource(strings = ["create", "dispatch", "cancel", "arrive"])
    fun `checked 직렬화 실패는 이동 전체를 롤백하고 같은 ID 재시도를 허용한다`(phase: String) {
        rollback(phase, true)
    }
    private fun rollback(phase: String, checked: Boolean) {
        val f = fixture(); val id = UUID.randomUUID(); val receiptId = UUID.randomUUID(); val request = body(f)
        if (phase != "create") service.create(id, request)
        if (phase == "arrive") service.dispatch(id, action)
        val before = snapshot()
        val run = { when (phase) {
            "create" -> service.create(id, request)
            "dispatch" -> service.dispatch(id, action)
            "cancel" -> service.cancel(id, action)
            else -> service.arrive(id, receiptId, arrival(f, 2))
        } }
        if (checked) Mockito.doAnswer { invocation ->
            if (invocation.arguments[0] is Map<*, *>) throw object : JsonProcessingException("이동 이벤트 직렬화 실패") {}
            invocation.callRealMethod()
        }.`when`(mapper).writeValueAsString(ArgumentMatchers.any())
        else jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT s7_fail CHECK (aggregate_type <> 'INVENTORY_TRANSFER') NOT VALID")
        try {
            if (checked) assertThrows(JsonProcessingException::class.java) { run() }
            else assertThrows(DataIntegrityViolationException::class.java) { run() }
        } finally {
            Mockito.reset(mapper)
            if (!checked) jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT s7_fail")
        }
        assertEquals(before, snapshot())
        run(); consistent()
    }

    @Test fun `새 애플리케이션은 미도착과 미출발을 조회하고 기한 후 같은 ID로 복구한다`() {
        val f = fixture(); val pending = UUID.randomUUID(); val transit = UUID.randomUUID(); val receiptId = UUID.randomUUID()
        val request = body(f, 4)
        service.create(pending, request); service.create(transit, request); service.dispatch(transit, action)
        service.arrive(transit, receiptId, arrival(f, 1))
        clock.time.set(request.expectedArrivalAt.plusSeconds(1))
        SpringApplicationBuilder(InventoryServiceApplication::class.java, TransferTestClockConfiguration::class.java)
            .run(*mapOf("server.port" to "0", "server.address" to "127.0.0.1", "spring.profiles.active" to "test",
                "spring.datasource.url" to postgres.jdbcUrl, "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password, "jwt.secret" to secret, "inventory.service-token" to internal,
                "dlt.topics" to "false", "outbox.publisher.enabled" to "false", "spring.kafka.listener.auto-startup" to "false",
                "inventory.reservations.expiry-enabled" to "false").map { (key, value) -> "--$key=$value" }.toTypedArray()).use { context ->
                val recovered = context.getBean(TransferService::class.java)
                assertNotSame(service, recovered)
                assertEquals(2L, recovered.list(f.source, true, true, 0, 10).totalElements)
                assertEquals(1, recovered.list(f.destination, true, true, 0, 1).content.size)
                assertEquals(3L, recovered.get(transit).lines.single().inTransitQuantity)
                assertEquals(TransferStatus.HELD, recovered.create(pending, request).transfer.status)
                recovered.arrive(transit, receiptId, arrival(f, 1))
                recovered.arrive(transit, UUID.randomUUID(), arrival(f, 3))
                recovered.cancel(pending, action)
                assertEquals(0L, recovered.list(null, true, true, 0, 10).totalElements)
            }
        assertEquals(10L, balances.findAll().sumOf { it.onHand }); consistent()
    }

    @Test fun `물리 도착은 만료 비활성 조건을 보존하며 판매 가능량으로 오인하지 않는다`() {
        val f = fixture(expires = LocalDate.now(clock))
        val id = UUID.randomUUID(); service.create(id, body(f)); service.dispatch(id, action)
        jdbc.update("UPDATE inventory_sku SET active=false WHERE id=?", f.sku)
        service.arrive(id, UUID.randomUUID(), arrival(f, 6))
        assertEquals(6L, inventory.stockSummary(f.sku, f.destination).single().onHand)
        assertEquals(0L, inventory.stockSummary(f.sku, f.destination).single().available)
        consistent()
    }

    @Test fun `ADMIN HTTP 입력 경계와 DB 수량 원장 참조 제약을 강제한다`() {
        val f = fixture(); val id = UUID.randomUUID(); val request = body(f)
        http("PUT", "/$id", request, 401, role = null)
        for (role in listOf(UserRole.USER, UserRole.SELLER)) http("PUT", "/$id", request, 403, role)
        http("PUT", "/$id", request, 401, role = null, bearer = internal)
        http("GET", "/$id", expected = 404)
        for (invalid in listOf(request.copy(lines = emptyList()), request.copy(lines = listOf(TransferQuantity(f.lot, 0))),
            request.copy(lines = request.lines + request.lines), request.copy(destinationWarehouseId = f.source),
            request.copy(expectedArrivalAt = clock.instant()), request.copy(reason = " "))) {
            http("PUT", "/$id", invalid, 400)
        }
        http("GET", "?size=201", expected = 400)
        http("GET", "?page=-1", expected = 400)
        val damaged = fixture(quality = LotQuality.DAMAGED)
        expectStatus(409) { service.create(UUID.randomUUID(), body(damaged)) }
        service.create(id, request)
        for (sql in listOf(
            "UPDATE inventory_stock_balance SET transfer_held=11 WHERE sku_id='${f.sku}'",
            "UPDATE inventory_stock_balance SET reserved=5 WHERE sku_id='${f.sku}'",
            "UPDATE inventory_transfer_line SET received_quantity=quantity+1 WHERE transfer_id='$id'",
            "UPDATE inventory_transfer_line SET sku_id='${damaged.sku}' WHERE transfer_id='$id'",
            "UPDATE inventory_stock_ledger SET transfer_held_delta=0 WHERE document_type='TRANSFER_HOLD'",
            "UPDATE inventory_transfer SET status='IN_TRANSIT' WHERE id='$id'")) {
            assertThrows(DataIntegrityViolationException::class.java) { jdbc.execute(sql) }
        }
        login("ROLE_USER"); assertThrows(AccessDeniedException::class.java) { service.get(id) }; login()
        consistent()
    }

    @Test fun `도착 보유량 Long 오버플로는 부분 반영 없이 롤백한다`() {
        val f = fixture(); val id = UUID.randomUUID(); service.create(id, body(f)); service.dispatch(id, action)
        jdbc.update("""INSERT INTO inventory_stock_balance (id,sku_id,warehouse_id,lot_id,on_hand,reserved,allocated,blocked)
            VALUES (?,?,?,?,?,0,0,0)""", UUID.randomUUID(), f.sku, f.destination, f.lot, Long.MAX_VALUE)
        val before = snapshot()
        assertThrows(ArithmeticException::class.java) { service.arrive(id, UUID.randomUUID(), arrival(f, 1)) }
        assertEquals(before, snapshot())
    }

    companion object {
        class MutableClock : Clock() {
            val time = AtomicReference(Instant.now())
            override fun instant(): Instant = time.get()
            override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        }
        val clock = MutableClock()
        private val secret = UUID.randomUUID().toString().repeat(2)
        private val internal = UUID.randomUUID().toString().repeat(2)
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @DynamicPropertySource @JvmStatic fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("jwt.secret") { secret }
            registry.add("inventory.service-token") { internal }
        }
    }
}
