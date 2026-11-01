package com.eventfulcommerce.inventory

import com.eventfulcommerce.common.auth.*
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.inventory.domain.*
import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.repository.*
import com.eventfulcommerce.inventory.service.*
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

@TestConfiguration
class ReservationTestClockConfiguration {
    @Bean @Primary fun testClock(): Clock = ReservationIntegrationTest.clock
}

@Testcontainers
@AutoConfigureMockMvc
@Import(ReservationTestClockConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["server.address=127.0.0.1",
    "dlt.topics=false", "spring.profiles.active=test", "outbox.publisher.enabled=false",
    "spring.kafka.listener.auto-startup=false", "inventory.reservations.expiry-enabled=false"])
class ReservationIntegrationTest {
    @Autowired lateinit var fulfillment: FulfillmentService
    @Autowired lateinit var service: ReservationService
    @Autowired lateinit var inventory: InventoryService
    @Autowired lateinit var reservations: ReservationRepository
    @Autowired lateinit var lines: ReservationLineRepository
    @Autowired lateinit var balances: StockBalanceRepository
    @Autowired lateinit var skus: InventorySkuRepository
    @Autowired lateinit var lots: InventoryLotRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var ledger: StockLedgerRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jwt: JwtTokenProvider
    @SpyBean lateinit var mapper: ObjectMapper
    private val actor = UUID.randomUUID()
    private fun login(authority: String = "INVENTORY_SERVICE") {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(actor, null,
            listOf(SimpleGrantedAuthority(authority)))
    }
    @BeforeEach fun clean() {
        Mockito.reset(mapper)
        clock.time.set(Instant.parse("2026-10-27T00:00:00Z"))
        jdbc.execute("TRUNCATE inventory_reservation, inventory_warehouse, inventory_supplier, inventory_sku, outbox_event, processed_event CASCADE")
        login()
    }
    @AfterEach fun clear() { SecurityContextHolder.clearContext() }
    private data class Fixture(val sku: UUID, val warehouse: Warehouse, val order: PurchaseOrderResponse)
    private fun fixture(quantity: Long = 100, skuId: UUID = UUID.randomUUID(), warehouse: Warehouse? = null,
        priority: Int = 0, active: Boolean = true, accepts: Boolean = true): Fixture {
        login("ROLE_ADMIN")
        if (!skus.existsById(skuId)) skus.saveAndFlush(InventorySku(skuId, "직매입"))
        val w = warehouse ?: inventory.createWarehouse(WarehouseRequest(UUID.randomUUID().toString(), "창고", "서울", active, accepts, priority))
        val supplier = inventory.createSupplier(SupplierRequest(UUID.randomUUID().toString(), "공급사"))
        val order = inventory.createPurchaseOrder(UUID.randomUUID(), PurchaseOrderRequest(supplier.id, w.id,
            listOf(PurchaseLineRequest(skuId, quantity, 1))))
        login()
        return Fixture(skuId, w, order)
    }
    private fun receive(f: Fixture, quantity: Long, code: String = UUID.randomUUID().toString(),
        quality: LotQuality = LotQuality.GOOD, expires: LocalDate = LocalDate.now(clock).plusDays(1)) {
        login("ROLE_ADMIN")
        inventory.receive(UUID.randomUUID(), InboundRequest(f.order.order.id, "예약 테스트 입고", listOf(
            InboundLineRequest(f.order.lines.single().id, code, expires, quality, quantity))))
        login()
    }
    private fun request(f: Fixture, quantity: Long = 1) = ReservationRequest(UUID.randomUUID(), UUID.randomUUID(),
        f.sku, quantity, clock.instant().plusSeconds(600))
    private fun status(expected: ReservationStatus, response: ReservationResponse) = assertEquals(expected, response.reservation.status)
    private fun expectStatus(expected: Int, action: () -> Unit) {
        assertEquals(expected, assertThrows(ResponseStatusException::class.java, action).statusCode.value())
    }
    private fun counts() = listOf("inventory_reservation", "inventory_reservation_line", "inventory_stock_ledger", "outbox_event")
        .associateWith { jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java) }
    private fun consistent() {
        for (b in balances.findAll()) {
            for ((state, amount) in listOf("RESERVED" to b.reserved, "ALLOCATED" to b.allocated)) {
                assertEquals(amount, jdbc.queryForObject("""SELECT COALESCE(sum(l.quantity),0)::bigint
                    FROM inventory_reservation_line l JOIN inventory_reservation r ON r.id=l.reservation_id
                    WHERE r.status=? AND l.warehouse_id=? AND l.lot_id=?""", Long::class.java, state, b.warehouseId, b.lotId))
            }
            val movements = ledger.findAll().filter { it.warehouseId == b.warehouseId && it.lotId == b.lotId }
            assertEquals(b.onHand, movements.sumOf { it.onHandDelta })
            assertEquals(b.blocked, movements.sumOf { it.blockedDelta })
            assertEquals(b.reserved, movements.sumOf { it.reservedDelta })
            assertEquals(b.allocated, movements.sumOf { it.allocatedDelta })
            assertTrue(b.reserved + b.allocated + b.blocked <= b.onHand)
        }
    }
    private fun parallel(actions: List<() -> Any>): List<Any> {
        val pool = Executors.newFixedThreadPool(actions.size); val start = CountDownLatch(1)
        return try {
            val futures = actions.map { action -> pool.submit<Any> {
                login()
                try { assertTrue(start.await(5, TimeUnit.SECONDS)); action() } finally { SecurityContextHolder.clearContext() }
            } }
            start.countDown()
            futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    @Test fun `FEFO는 유효 정상 로트를 한 창고에서 전량 확보하고 우선순위 부족 창고를 건너뛴다`() {
        val first = fixture(priority = 0); receive(first, 2)
        val f = fixture(skuId = first.sku, priority = 1)
        receive(f, 3, "EARLY"); receive(f, 7, "LATE", expires = LocalDate.now(clock).plusDays(3))
        receive(f, 10, "EXPIRED", expires = LocalDate.now(clock))
        receive(f, 10, "DAMAGED", quality = LotQuality.DAMAGED)
        receive(f, 10, "INSPECTION", quality = LotQuality.INSPECTION)
        val result = service.reserve(UUID.randomUUID(), request(f, 5))
        status(ReservationStatus.RESERVED, result)
        assertEquals(f.warehouse.id, result.reservation.warehouseId)
        val byCode = result.lines.associate { line ->
            jdbc.queryForObject("SELECT code FROM inventory_lot WHERE id=?", String::class.java, line.lotId) to line.quantity
        }
        assertEquals(mapOf("EARLY" to 3L, "LATE" to 2L), byCode)
        assertEquals(42L, balances.findAll().sumOf { it.onHand }); consistent()
    }

    @Test fun `창고 분할 부족은 영속 거절이며 재입고와 재시도로 되살아나지 않는다`() {
        val f = fixture(); receive(f, 3)
        val second = fixture(skuId = f.sku); receive(second, 3)
        val id = UUID.randomUUID(); val body = request(f, 5)
        val result = service.reserve(id, body)
        status(ReservationStatus.REJECTED, result)
        assertEquals("NO_SINGLE_WAREHOUSE_STOCK", result.reservation.reason)
        receive(f, 10)
        status(ReservationStatus.REJECTED, service.reserve(id, body))
        assertEquals(0L, lines.count()); consistent()
    }

    @Test fun `비활성 SKU와 신규 예약 중지 창고는 확보하지 않는다`() {
        val f = fixture(accepts = false); receive(f, 10)
        status(ReservationStatus.REJECTED, service.reserve(UUID.randomUUID(), request(f)))
        val other = fixture(); receive(other, 10)
        jdbc.update("UPDATE inventory_sku SET active=false WHERE id=?", other.sku)
        assertEquals("SKU_UNAVAILABLE", service.reserve(UUID.randomUUID(), request(other)).reservation.reason)
        consistent()
    }

    @Test fun `같은 ID 여섯 동시 재전송과 응답 유실 후 조회는 한 번만 효과를 낸다`() {
        val f = fixture(); receive(f, 10)
        val id = UUID.randomUUID(); val body = request(f, 4)
        val before = outbox.count()
        parallel(List(6) { { service.reserve(id, body) } })
        val found = service.get(id)
        status(ReservationStatus.RESERVED, found)
        assertEquals(1L, reservations.count()); assertEquals(1L, lines.count()); assertEquals(before + 1, outbox.count())
        assertEquals(4L, balances.findAll().single().reserved)
        for (changed in listOf(body.copy(quantity = 5), body.copy(skuId = UUID.randomUUID()),
            body.copy(orderAttemptId = UUID.randomUUID()), body.copy(orderItemId = UUID.randomUUID()),
            body.copy(expiresAt = body.expiresAt.plusSeconds(1)))) {
            expectStatus(409) { service.reserve(id, changed) }
            expectStatus(409) { service.release(id, changed) }
            expectStatus(409) { service.allocate(id, changed) }
        }
        assertEquals(found.reservation.createdAt, service.reserve(id, body).reservation.createdAt); consistent()
    }

    @Test fun `선행 해제는 미등록 SKU도 종결하고 늦은 생성과 할당을 막는다`() {
        val f = fixture(); receive(f, 10)
        for (body in listOf(request(f, 3), request(f).copy(skuId = UUID.randomUUID()))) {
            val id = UUID.randomUUID()
            status(ReservationStatus.RELEASED, service.release(id, body))
            status(ReservationStatus.RELEASED, service.reserve(id, body))
            status(ReservationStatus.RELEASED, service.allocate(id, body))
            expectStatus(409) { service.reserve(id, body.copy(quantity = 4)) }
        }
        assertEquals(0L, lines.count()); consistent()
    }

    @Test fun `생성과 해제 순서 경합은 반복해도 해제 종결과 수량을 보존한다`() {
        val f = fixture(); receive(f, 10)
        repeat(6) {
            val id = UUID.randomUUID(); val body = request(f, 3)
            parallel(listOf({ service.reserve(id, body) }, { service.release(id, body) }))
            status(ReservationStatus.RELEASED, service.get(id))
        }
        assertEquals(0L, balances.findAll().single().reserved); consistent()
    }

    @Test fun `마지막 다로트 재고 열두 요청은 초과 예약과 음수가 없다`() {
        val f = fixture(); receive(f, 4); receive(f, 5)
        val results = parallel(List(12) { { service.reserve(UUID.randomUUID(), request(f, 3)) } }).map { it as ReservationResponse }
        assertEquals(3, results.count { it.reservation.status == ReservationStatus.RESERVED })
        assertEquals(9, results.count { it.reservation.status == ReservationStatus.REJECTED })
        assertEquals(9L, balances.findAll().sumOf { it.reserved }); consistent()
    }

    @Test fun `할당은 실물 수량을 보존하고 중복 및 TTL 이후에도 자동 해제되지 않는다`() {
        val f = fixture(); receive(f, 10)
        val id = UUID.randomUUID(); val body = request(f, 4)
        service.reserve(id, body)
        status(ReservationStatus.ALLOCATED, service.allocate(id, body))
        val before = counts()
        clock.time.set(body.expiresAt.plusSeconds(1))
        service.expire(id)
        status(ReservationStatus.ALLOCATED, service.get(id))
        status(ReservationStatus.ALLOCATED, service.allocate(id, body))
        expectStatus(409) { service.release(id, body) }
        assertEquals(before, counts())
        val balance = balances.findAll().single()
        assertEquals(10L, balance.onHand); assertEquals(4L, balance.allocated); assertEquals(0L, balance.reserved); consistent()
    }

    @Test fun `TTL 경계는 배치 없이 조회와 할당에서 만료하고 재활성화하지 않는다`() {
        val f = fixture(); receive(f, 10)
        val ids = List(2) { UUID.randomUUID() }; val body = request(f, 3)
        ids.forEach { service.reserve(it, body) }
        clock.time.set(body.expiresAt)
        status(ReservationStatus.EXPIRED, service.get(ids[0]))
        status(ReservationStatus.EXPIRED, service.allocate(ids[1], body))
        ids.forEach { status(ReservationStatus.EXPIRED, service.reserve(it, body)) }
        status(ReservationStatus.EXPIRED, service.reserve(UUID.randomUUID(), body))
        assertEquals(0L, balances.findAll().single().reserved); consistent()
    }

    @Test fun `할당과 해제 경쟁은 승자 상태와 수량을 보존한다`() {
        val f = fixture(); receive(f, 10)
        repeat(4) {
            val id = UUID.randomUUID(); val body = request(f)
            service.reserve(id, body)
            parallel(listOf({ service.allocate(id, body) }, {
                try { service.release(id, body) } catch (ex: ResponseStatusException) { assertEquals(409, ex.statusCode.value()); "CONFLICT" }
            }))
            assertTrue(service.get(id).reservation.status in listOf(ReservationStatus.ALLOCATED, ReservationStatus.RELEASED))
            consistent()
        }
    }

    @Test fun `만료와 할당 해제의 DB 경쟁은 만료를 한 번 기록한다`() {
        val f = fixture(); receive(f, 10)
        repeat(4) {
            val id = UUID.randomUUID(); val body = request(f, 2)
            service.reserve(id, body); clock.time.set(body.expiresAt)
            parallel(listOf({ service.allocate(id, body) }, { service.release(id, body) }, { service.expire(id); "EXPIRED" }))
            status(ReservationStatus.EXPIRED, service.get(id))
            assertEquals(1L, ledger.findAll().count { it.documentId == id && it.documentType == "EXPIRATION" }.toLong())
            consistent()
        }
    }

    @Test fun `예약 뒤 날짜 경계 품질 SKU 창고 변경은 할당 전에 차단하고 예약만 해제한다`() {
        for (condition in listOf("date", "quality", "sku", "warehouse")) {
            clock.time.set(Instant.parse("2026-10-27T14:59:00Z"))
            val f = fixture(); receive(f, 10)
            val id = UUID.randomUUID(); val body = request(f, 4)
            service.reserve(id, body)
            when (condition) {
                "date" -> clock.time.set(clock.instant().plusSeconds(120))
                "quality" -> jdbc.update("UPDATE inventory_lot SET quality='DAMAGED' WHERE sku_id=?", f.sku)
                "sku" -> jdbc.update("UPDATE inventory_sku SET active=false WHERE id=?", f.sku)
                "warehouse" -> jdbc.update("UPDATE inventory_warehouse SET active=false WHERE id=?", f.warehouse.id)
            }
            status(ReservationStatus.INVALIDATED, service.allocate(id, body))
            status(ReservationStatus.INVALIDATED, service.reserve(id, body)); consistent()
        }
    }

    @Test fun `예약과 해제 할당 Outbox 실패는 확보 기록 잔량 원장 상태를 함께 롤백한다`() {
        val f = fixture(); receive(f, 10)
        val id = UUID.randomUUID(); val body = request(f, 4)
        val before = counts()
        fun fail(action: () -> Unit) {
            jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT test_fail_reservation CHECK (aggregate_type <> 'INVENTORY_RESERVATION') NOT VALID")
            try { assertThrows(Exception::class.java, action) }
            finally { jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT test_fail_reservation") }
        }
        fail { service.reserve(id, body) }
        assertEquals(before, counts()); assertEquals(0L, balances.findAll().single().reserved)
        service.reserve(id, body)
        val reserved = counts()
        fail { service.release(id, body) }; assertEquals(reserved, counts())
        fail { service.allocate(id, body) }; assertEquals(reserved, counts())
        status(ReservationStatus.RESERVED, service.get(id)); assertEquals(4L, balances.findAll().single().reserved); consistent()
        service.allocate(id, body); consistent()
    }

    @Test fun `checked 직렬화 실패는 예약 수량과 문서 원장을 롤백해 같은 ID로 재시도한다`() {
        val f = fixture(); receive(f, 10)
        val id = UUID.randomUUID(); val body = request(f, 4); val before = counts()
        Mockito.doAnswer { invocation ->
            if (invocation.arguments[0] is Map<*, *>) throw object : JsonProcessingException("강제 예약 직렬화 실패") {}
            invocation.callRealMethod()
        }.`when`(mapper).writeValueAsString(ArgumentMatchers.any())
        assertThrows(JsonProcessingException::class.java) { service.reserve(id, body) }
        assertEquals(before, counts()); assertEquals(0L, balances.findAll().single().reserved)
        Mockito.reset(mapper)
        status(ReservationStatus.RESERVED, service.reserve(id, body)); consistent()
    }

    @Test fun `재시작한 애플리케이션은 DB 예약을 복구하고 만료를 재개하며 할당은 유지한다`() {
        val f = fixture(); receive(f, 10)
        val expired = UUID.randomUUID(); val allocated = UUID.randomUUID(); val body = request(f, 3)
        service.reserve(expired, body); service.reserve(allocated, body); service.allocate(allocated, body)
        clock.time.set(body.expiresAt)
        SpringApplicationBuilder(InventoryServiceApplication::class.java, ReservationTestClockConfiguration::class.java)
            .run(*mapOf("server.port" to "0", "server.address" to "127.0.0.1", "spring.profiles.active" to "test",
                "spring.datasource.url" to postgres.jdbcUrl, "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password, "jwt.secret" to testSecret,
                "inventory.service-token" to testToken, "dlt.topics" to "false", "outbox.publisher.enabled" to "false",
                "spring.kafka.listener.auto-startup" to "false", "inventory.reservations.expiry-delay-ms" to "3600000").map { (key, value) -> "--$key=$value" }.toTypedArray()).use { restarted ->
                assertNotSame(service, restarted.getBean(ReservationService::class.java))
                restarted.getBean(ReservationExpiryScheduler::class.java).expireDue()
                val recovered = restarted.getBean(ReservationService::class.java)
                status(ReservationStatus.EXPIRED, recovered.get(expired))
                status(ReservationStatus.ALLOCATED, recovered.get(allocated))
            }
        assertEquals(3L, balances.findAll().single().allocated); assertEquals(0L, balances.findAll().single().reserved); consistent()
    }

    @Test fun `내부 HTTP는 전용 토큰만 받고 입력과 원본 충돌을 검사한다`() {
        val f = fixture(); receive(f, 10)
        val id = UUID.randomUUID(); val body = request(f, 3)
        SecurityContextHolder.clearContext()
        for (token in listOf("", "invalid", jwt.createAccessToken(actor, UserRole.ADMIN), jwt.createRefreshToken(actor, UserRole.ADMIN))) {
            mvc.perform(get("/internal/reservations/$id").header("Authorization", "Bearer $token")
                .header("X-User-Role", "ADMIN")).andExpect(status().isUnauthorized)
        }
        mvc.perform(put("/internal/reservations/$id").header("Authorization", "Bearer $testToken")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isOk).andExpect(jsonPath("$.reservation.status").value("RESERVED"))
            .andExpect(jsonPath("$.reservation.requestContent").doesNotExist())
        mvc.perform(get("/internal/reservations/$id").header("Authorization", "Bearer $testToken"))
            .andExpect(status().isOk).andExpect(jsonPath("$.lines[0].quantity").value(3))
        mvc.perform(post("/internal/reservations/$id/allocate").header("Authorization", "Bearer $testToken")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
            .andExpect(status().isOk).andExpect(jsonPath("$.reservation.status").value("ALLOCATED"))
        mvc.perform(get("/inventory/warehouses").header("Authorization", "Bearer $testToken")).andExpect(status().isUnauthorized)
        for (invalid in listOf(body.copy(quantity = 0), body.copy(quantity = -1), body.copy(expiresAt = clock.instant().plusSeconds(901)))) {
            mvc.perform(put("/internal/reservations/" + UUID.randomUUID()).header("Authorization", "Bearer $testToken")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(invalid))).andExpect(status().isBadRequest)
        }
        mvc.perform(get("/internal/reservations/" + UUID.randomUUID()).header("Authorization", "Bearer $testToken"))
            .andExpect(status().isNotFound)
        login("ROLE_ADMIN"); assertThrows(AccessDeniedException::class.java) { service.get(id) }
        login("ROLE_USER"); assertThrows(AccessDeniedException::class.java) { service.reserve(UUID.randomUUID(), body) }
    }

    @Test fun `DB는 예약 품목의 SKU 창고 로트 수량 불일치와 잘못된 원장을 거부한다`() {
        val f = fixture(); receive(f, 10)
        val id = UUID.randomUUID(); service.reserve(id, request(f, 3))
        val line = lines.findAll().single()
        for (sql in listOf(
            "UPDATE inventory_reservation_line SET quantity=0 WHERE id='${line.id}'",
            "UPDATE inventory_reservation_line SET sku_id='${UUID.randomUUID()}' WHERE id='${line.id}'",
            "UPDATE inventory_reservation_line SET warehouse_id='${UUID.randomUUID()}' WHERE id='${line.id}'",
            "UPDATE inventory_stock_ledger SET reserved_delta=0 WHERE document_type='RESERVATION'",
            "UPDATE inventory_reservation SET status='UNEXPECTED' WHERE id='$id'")) {
            assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) { jdbc.execute(sql) }
        }
        consistent()
    }

    private data class OutboundFixture(val id: UUID, val request: ReservationRequest, val action: OutboundActionRequest)
    private fun outboundFixture(quantity: Long = 2): OutboundFixture {
        val f = fixture(); receive(f, 10)
        val id = UUID.randomUUID(); val request = request(f, quantity)
        service.reserve(id, request); service.allocate(id, request)
        fulfillment.create(com.eventfulcommerce.common.DirectFulfillmentPayload(request.orderAttemptId, actor, UUID.randomUUID(),
            request.orderItemId, id, f.sku, f.warehouse.id, quantity))
        val action = OutboundActionRequest("창고 전량 확인", lines.findByReservationIdOrderByLotId(id).map { LotQuantity(it.lotId, it.quantity) })
        return OutboundFixture(id, request, action)
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun multiLotFulfillmentUsesSameOrderForDatabaseAndRequests(returned: Boolean) {
        val f = fixture()
        val ids = listOf(UUID.fromString("08e7f45f-8d51-4907-9e62-bee0a2bc3a92"),
            UUID.fromString("9546cba1-3944-481b-a03f-676c34c124c2"))
        for (id in ids) {
            lots.saveAndFlush(InventoryLot(id, f.sku, f.order.order.supplierId, id.toString(), LocalDate.now(clock).plusDays(1), LotQuality.GOOD))
            receive(f, 2, id.toString())
        }
        val id = UUID.randomUUID(); val request = request(f, 3)
        service.reserve(id, request); service.allocate(id, request)
        fulfillment.create(com.eventfulcommerce.common.DirectFulfillmentPayload(request.orderAttemptId, actor, UUID.randomUUID(),
            request.orderItemId, id, f.sku, f.warehouse.id, 3))
        val action = OutboundActionRequest("복수 로트 전량 확인", lines.findByReservationIdOrderByLotId(id).map { LotQuantity(it.lotId, it.quantity) })
        assertEquals(ids, action.lines.map { it.lotId })
        assertNotEquals(ids, ids.sorted())
        login("ROLE_ADMIN")
        expectStatus(409) { fulfillment.act(id, "pick", action.copy(lines = listOf(action.lines.first()))) }
        fulfillment.act(id, "pick", action)
        val beforePick = counts()
        fulfillment.act(id, "pick", action.copy(lines = action.lines.reversed()))
        assertEquals(beforePick, counts())
        if (returned) {
            fulfillment.act(id, "pack", action); fulfillment.act(id, "ship", action)
            val receipt = UUID.randomUUID()
            fulfillment.requestReturn(receipt, ReturnRequest(id, "복수 로트 반품")); fulfillment.receiveReturn(receipt)
            val inspection = ReturnInspectionRequest("전량 정상", action.lines.map { ReturnLotResult(it.lotId, it.quantity, 0) })
            fulfillment.inspectReturn(receipt, inspection)
            val before = counts()
            fulfillment.inspectReturn(receipt, inspection.copy(lines = inspection.lines.reversed()))
            assertEquals(before, counts()); assertEquals(4L, balances.findAll().sumOf { it.onHand })
        } else {
            login(); service.cancel(id, request)
            login("ROLE_ADMIN"); fulfillment.decide(id, CancellationDecisionRequest(false, "출고 유지"))
            clock.time.set(clock.instant().plusSeconds(86400))
            fulfillment.cancelIneligible(id, action)
            val before = counts()
            fulfillment.cancelIneligible(id, action.copy(lines = action.lines.reversed()))
            assertEquals(before, counts()); assertEquals(0L, balances.findAll().sumOf { it.allocated })
        }
        consistent()
    }

    @Test fun `S6 피킹 포장은 보유를 보존하고 출고는 한 번만 차감하며 다른 로트를 거부한다`() {
        val f = outboundFixture()
        login("ROLE_ADMIN")
        expectStatus(409) { fulfillment.act(f.id, "ship", f.action) }
        expectStatus(409) { fulfillment.act(f.id, "pick", f.action.copy(lines = listOf(LotQuantity(UUID.randomUUID(), 2)))) }
        fulfillment.act(f.id, "pick", f.action); fulfillment.act(f.id, "pack", f.action)
        assertEquals(10L, balances.findAll().single().onHand); assertEquals(2L, balances.findAll().single().allocated)
        fulfillment.act(f.id, "ship", f.action)
        val before = counts()
        fulfillment.act(f.id, "ship", f.action); fulfillment.act(f.id, "pick", f.action)
        assertEquals(before, counts())
        assertEquals(8L, balances.findAll().single().onHand); assertEquals(0L, balances.findAll().single().allocated)
        expectStatus(409) { fulfillment.act(f.id, "ship", f.action.copy(reason = "다른 출고")) }
        login(); assertEquals("REJECTED", service.cancel(f.id, f.request).cancellationStatus)
        assertEquals(ReservationStatus.SHIPPED, service.get(f.id).reservation.status); consistent()
    }

    @Test fun `S6 선행 취소와 늦은 출고 지시가 종결 예약을 되살리지 않는다`() {
        val f = fixture(); receive(f, 10)
        val id = UUID.randomUUID(); val request = request(f, 2)
        service.reserve(id, request); service.allocate(id, request); service.cancel(id, request)
        fulfillment.create(com.eventfulcommerce.common.DirectFulfillmentPayload(request.orderAttemptId, actor, UUID.randomUUID(),
            request.orderItemId, id, f.sku, f.warehouse.id, 2))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inventory_outbound", Int::class.java))
        assertEquals(10L, balances.findAll().single().onHand); consistent()
    }

    @Test fun `S6 피킹 후 창고 승인 전에는 출고 환원 불가이며 승인 중복과 다른 결정을 구분한다`() {
        val f = outboundFixture(); login("ROLE_ADMIN"); fulfillment.act(f.id, "pick", f.action)
        login(); assertEquals("PENDING", service.cancel(f.id, f.request).cancellationStatus)
        assertEquals(2L, balances.findAll().single().allocated)
        login("ROLE_ADMIN"); expectStatus(409) { fulfillment.act(f.id, "pack", f.action) }
        val decision = CancellationDecisionRequest(true, "실물 회수 확인")
        fulfillment.decide(f.id, decision); val before = counts(); fulfillment.decide(f.id, decision)
        assertEquals(before, counts()); expectStatus(409) { fulfillment.decide(f.id, decision.copy(approved = false)) }
        assertEquals(10L, balances.findAll().single().onHand); assertEquals(0L, balances.findAll().single().allocated)
        login(); status(ReservationStatus.CANCELED, service.cancel(f.id, f.request)); consistent()
    }

    @Test fun `S6 창고 거절 뒤 재취소는 거절을 보존하고 실제 출고를 허용한다`() {
        val f = outboundFixture(); login("ROLE_ADMIN"); fulfillment.act(f.id, "pick", f.action)
        login(); service.cancel(f.id, f.request)
        login("ROLE_ADMIN"); fulfillment.decide(f.id, CancellationDecisionRequest(false, "창고 취소 불가"))
        login(); assertEquals("REJECTED", service.cancel(f.id, f.request).cancellationStatus)
        login("ROLE_ADMIN"); fulfillment.act(f.id, "pack", f.action); fulfillment.act(f.id, "ship", f.action)
        assertEquals(8L, balances.findAll().single().onHand); consistent()
    }

    @Test fun `S6 취소 피킹 출고 경합은 같은 예약 잠금으로 재고를 중복 복원하지 않는다`() {
        repeat(4) {
            val f = outboundFixture()
            parallel(listOf({
                login("ROLE_ADMIN")
                try { fulfillment.act(f.id, "pick", f.action); "PICKED" } catch (e: ResponseStatusException) { assertEquals(409, e.statusCode.value()); "CANCELED" }
            }, { service.cancel(f.id, f.request) }))
            login()
            val state = service.get(f.id)
            if (state.reservation.status == ReservationStatus.CANCELED) assertEquals(0L, balances.findAll().filter { it.skuId == f.request.skuId }.single().allocated)
            else {
                assertEquals("PENDING", state.cancellationStatus)
                login("ROLE_ADMIN"); fulfillment.decide(f.id, CancellationDecisionRequest(false, "출고 진행")); fulfillment.act(f.id, "pack", f.action)
                parallel(listOf({ login("ROLE_ADMIN"); fulfillment.act(f.id, "ship", f.action) }, { service.cancel(f.id, f.request) }))
                login(); status(ReservationStatus.SHIPPED, service.get(f.id))
                assertEquals(8L, balances.findAll().filter { it.skuId == f.request.skuId }.single().onHand)
            }
            consistent()
        }
    }

    @Test fun `S6 출고 전 사용기한 경계는 실물 차감을 막고 창고 승인으로만 해제한다`() {
        val f = outboundFixture(); login("ROLE_ADMIN"); fulfillment.act(f.id, "pick", f.action); fulfillment.act(f.id, "pack", f.action)
        clock.time.set(clock.instant().plusSeconds(86400))
        expectStatus(409) { fulfillment.act(f.id, "ship", f.action) }
        assertEquals(10L, balances.findAll().single().onHand)
        login(); service.cancel(f.id, f.request)
        login("ROLE_ADMIN"); fulfillment.decide(f.id, CancellationDecisionRequest(true, "사용기한 경과 회수")); consistent()
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `S6 거절 후 만료된 피킹 포장 품목은 전량 회수로만 종결하고 원 거절을 보존한다`(packed: Boolean) {
        val f = outboundFixture(); login("ROLE_ADMIN"); fulfillment.act(f.id, "pick", f.action)
        login(); service.cancel(f.id, f.request)
        login("ROLE_ADMIN")
        val decision = CancellationDecisionRequest(false, "최초 회수 불가")
        fulfillment.decide(f.id, decision)
        if (packed) fulfillment.act(f.id, "pack", f.action)
        val original = jdbc.queryForObject("SELECT decision_content FROM inventory_outbound WHERE id=?", String::class.java, f.id)
        expectStatus(409) { fulfillment.cancelIneligible(f.id, f.action) }
        clock.time.set(clock.instant().plusSeconds(86400))
        expectStatus(409) { fulfillment.act(f.id, if (packed) "ship" else "pack", f.action) }
        expectStatus(409) { fulfillment.decide(f.id, decision.copy(approved = true)) }
        expectStatus(409) { fulfillment.cancelIneligible(f.id, f.action.copy(lines = listOf(LotQuantity(UUID.randomUUID(), 2)))) }
        assertEquals(2L, balances.findAll().single().allocated)
        fulfillment.cancelIneligible(f.id, f.action)
        val before = counts()
        fulfillment.cancelIneligible(f.id, f.action)
        assertFalse(fulfillment.decide(f.id, decision).outbound.cancellationApproved!!)
        assertEquals(original, jdbc.queryForObject("SELECT decision_content FROM inventory_outbound WHERE id=?", String::class.java, f.id))
        assertEquals(before, counts())
        expectStatus(409) { fulfillment.cancelIneligible(f.id, f.action.copy(reason = "다른 회수")) }
        assertEquals(OutboundStatus.CANCELED, fulfillment.get(f.id).outbound.status)
        login(); val result = service.cancel(f.id, f.request)
        status(ReservationStatus.CANCELED, result); assertNull(result.cancellationStatus)
        assertEquals(10L, balances.findAll().single().onHand); assertEquals(0L, balances.findAll().single().allocated)
        assertEquals(1, outbox.findAll().count { it.eventType == "OUTBOUND_INELIGIBILITY_CANCELED" }); consistent()
    }

    @Test fun `S6 부적격 회수 이벤트 실패는 기존 거절을 보존하고 할당 해제 전체를 롤백한다`() {
        val f = outboundFixture(); login("ROLE_ADMIN"); fulfillment.act(f.id, "pick", f.action)
        login(); service.cancel(f.id, f.request)
        login("ROLE_ADMIN"); fulfillment.decide(f.id, CancellationDecisionRequest(false, "최초 거절"))
        clock.time.set(clock.instant().plusSeconds(86400))
        val before = counts()
        Mockito.doThrow(object : JsonProcessingException("회수 이벤트 실패") {}).`when`(mapper)
            .writeValueAsString(ArgumentMatchers.any(com.eventfulcommerce.common.DirectFulfillmentPayload::class.java))
        assertThrows(JsonProcessingException::class.java) { fulfillment.cancelIneligible(f.id, f.action) }
        Mockito.reset(mapper)
        assertEquals(before, counts()); assertEquals(OutboundStatus.PICKING, fulfillment.get(f.id).outbound.status)
        assertNull(fulfillment.get(f.id).outbound.ineligibilityCancellationContent)
        assertEquals(2L, balances.findAll().single().allocated)
        fulfillment.cancelIneligible(f.id, f.action); consistent()
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `S6 거절 후 부적격 회수와 출고 경합은 한 종결 효과만 기록한다`(expired: Boolean) {
        repeat(4) {
            val f = outboundFixture(); login("ROLE_ADMIN"); fulfillment.act(f.id, "pick", f.action)
            login(); service.cancel(f.id, f.request)
            login("ROLE_ADMIN"); fulfillment.decide(f.id, CancellationDecisionRequest(false, "최초 거절"))
            fulfillment.act(f.id, "pack", f.action)
            if (expired) clock.time.set(clock.instant().plusSeconds(86400))
            val results = parallel(listOf(
                { login("ROLE_ADMIN"); try { fulfillment.cancelIneligible(f.id, f.action); "CANCELED" }
                    catch (e: ResponseStatusException) { assertEquals(409, e.statusCode.value()); "CONFLICT" } },
                { login("ROLE_ADMIN"); try { fulfillment.act(f.id, "ship", f.action); "SHIPPED" }
                    catch (e: ResponseStatusException) { assertEquals(409, e.statusCode.value()); "CONFLICT" } }))
            assertEquals(1, results.count { it == "CONFLICT" })
            assertTrue((if (expired) "CANCELED" else "SHIPPED") in results)
            login(); status(if (expired) ReservationStatus.CANCELED else ReservationStatus.SHIPPED, service.get(f.id))
            val balance = balances.findAll().single { it.skuId == f.request.skuId }
            assertEquals(if (expired) 10L else 8L, balance.onHand); assertEquals(0L, balance.allocated); consistent()
        }
    }

    @Test fun `S6 반품 접수 수령은 미복원이며 검수 정상 불량 원장과 중복 효과를 보존한다`() {
        val f = outboundFixture(); login("ROLE_ADMIN")
        fulfillment.act(f.id, "pick", f.action); fulfillment.act(f.id, "pack", f.action); fulfillment.act(f.id, "ship", f.action)
        val id = UUID.randomUUID(); val body = ReturnRequest(f.id, "전량 반품")
        fulfillment.requestReturn(id, body); fulfillment.requestReturn(id, body)
        expectStatus(409) { fulfillment.requestReturn(UUID.randomUUID(), body) }
        val inspection = ReturnInspectionRequest("실물 검수", listOf(ReturnLotResult(f.action.lines.single().lotId, 1, 1)))
        expectStatus(409) { fulfillment.inspectReturn(id, inspection) }
        assertEquals(8L, balances.findAll().single().onHand)
        fulfillment.receiveReturn(id); fulfillment.receiveReturn(id)
        assertEquals(8L, balances.findAll().single().onHand)
        fulfillment.inspectReturn(id, inspection)
        val before = counts(); fulfillment.inspectReturn(id, inspection); fulfillment.receiveReturn(id)
        assertEquals(before, counts())
        assertEquals(10L, balances.findAll().sumOf { it.onHand }); assertEquals(1L, balances.findAll().sumOf { it.blocked })
        assertEquals(9L, balances.findAll().sumOf { it.onHand - it.blocked })
        expectStatus(409) { fulfillment.inspectReturn(id, inspection.copy(reason = "다른 검수")) }
        assertEquals(ReturnStatus.INSPECTED, fulfillment.getReturn(id).status); consistent()
    }

    @Test fun `S6 출고 Outbox 직렬화 실패는 문서 잔량 원장과 상태를 동반 롤백한다`() {
        val f = outboundFixture(); login("ROLE_ADMIN")
        fulfillment.act(f.id, "pick", f.action); fulfillment.act(f.id, "pack", f.action)
        val before = counts()
        Mockito.doThrow(object : JsonProcessingException("S6 출고 이벤트 실패") {}).`when`(mapper)
            .writeValueAsString(ArgumentMatchers.any(com.eventfulcommerce.common.DirectFulfillmentPayload::class.java))
        assertThrows(JsonProcessingException::class.java) { fulfillment.act(f.id, "ship", f.action) }
        Mockito.reset(mapper)
        assertEquals(before, counts()); assertEquals(OutboundStatus.PACKED, fulfillment.get(f.id).outbound.status)
        assertEquals(10L, balances.findAll().single().onHand); assertEquals(2L, balances.findAll().single().allocated); consistent()
    }

    @Test fun `S6 창고 API는 ADMIN JWT만 받고 위조 헤더와 내부 토큰을 거부한다`() {
        val f = outboundFixture(); SecurityContextHolder.clearContext()
        mvc.perform(get("/inventory/outbounds/${f.id}").header("X-User-Role", "ADMIN")).andExpect(status().isUnauthorized)
        mvc.perform(get("/inventory/outbounds/${f.id}").header("Authorization", "Bearer $testToken")).andExpect(status().isUnauthorized)
        mvc.perform(get("/inventory/outbounds/${f.id}").header("Authorization", "Bearer ${jwt.createAccessToken(actor, UserRole.USER)}")).andExpect(status().isForbidden)
        mvc.perform(get("/inventory/outbounds/${f.id}").header("Authorization", "Bearer ${jwt.createAccessToken(actor, UserRole.ADMIN)}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.outbound.requestContent").doesNotExist())
        val recoveryPath = "/inventory/outbounds/${f.id}/ineligibility-cancellation"
        for (token in listOf("", testToken)) mvc.perform(put(recoveryPath).header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(f.action))).andExpect(status().isUnauthorized)
        mvc.perform(put(recoveryPath).header("Authorization", "Bearer ${jwt.createAccessToken(actor, UserRole.USER)}")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(f.action))).andExpect(status().isForbidden)
        mvc.perform(put(recoveryPath).header("Authorization", "Bearer ${jwt.createAccessToken(actor, UserRole.ADMIN)}")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(f.action.copy(lines = emptyList()))))
            .andExpect(status().isBadRequest)
    }

    @Test fun `S6 같은 반품 ID의 다른 출고 동시 접수는 최초 원본을 덮어쓰지 않는다`() {
        val first = outboundFixture(); val second = outboundFixture()
        login("ROLE_ADMIN")
        for (f in listOf(first, second)) for (action in listOf("pick", "pack", "ship")) fulfillment.act(f.id, action, f.action)
        val id = UUID.randomUUID()
        val results = parallel(listOf(first, second).map { f -> {
            login("ROLE_ADMIN")
            try { fulfillment.requestReturn(id, ReturnRequest(f.id, "경합 반품")); "CREATED" }
            catch (e: ResponseStatusException) { assertEquals(409, e.statusCode.value()); "CONFLICT" }
        } })
        assertEquals(1, results.count { it == "CREATED" }); assertEquals(1, results.count { it == "CONFLICT" })
        login("ROLE_ADMIN")
        val winner = fulfillment.getReturn(id).outboundId
        val loser = if (winner == first.id) second.id else first.id
        expectStatus(409) { fulfillment.requestReturn(id, ReturnRequest(loser, "경합 반품")) }
        assertEquals(winner, fulfillment.getReturn(id).outboundId)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inventory_return_receipt", Int::class.java)); consistent()
    }

    @Test fun `S6 다른 사용기한의 불량 로트에 반품을 섞지 않고 검수 전체를 롤백한다`() {
        val f = outboundFixture(); login("ROLE_ADMIN")
        for (action in listOf("pick", "pack", "ship")) fulfillment.act(f.id, action, f.action)
        val id = UUID.randomUUID(); fulfillment.requestReturn(id, ReturnRequest(f.id, "사용기한 대조")); fulfillment.receiveReturn(id)
        val lot = jdbc.queryForMap("SELECT * FROM inventory_lot WHERE id=?", f.action.lines.single().lotId)
        jdbc.update("INSERT INTO inventory_lot (id, sku_id, supplier_id, code, expires_on, quality) VALUES (?, ?, ?, ?, ?, 'DAMAGED')",
            UUID.randomUUID(), lot["sku_id"], lot["supplier_id"], lot["code"], LocalDate.now(clock).plusDays(2))
        val before = counts()
        expectStatus(409) { fulfillment.inspectReturn(id, ReturnInspectionRequest("기한 충돌", listOf(ReturnLotResult(f.action.lines.single().lotId, 1, 1)))) }
        assertEquals(before, counts()); assertEquals(ReturnStatus.RECEIVED, fulfillment.getReturn(id).status)
        assertEquals(8L, balances.findAll().sumOf { it.onHand }); consistent()
    }

    companion object {
        class MutableClock : Clock() {
            val time = AtomicReference(Instant.parse("2026-10-27T00:00:00Z"))
            override fun instant(): Instant = time.get()
            override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        }
        val clock = MutableClock()
        private val testSecret = UUID.randomUUID().toString().repeat(2)
        private val testToken = UUID.randomUUID().toString().repeat(2)
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @DynamicPropertySource @JvmStatic fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("jwt.secret") { testSecret }
            registry.add("inventory.service-token") { testToken }
        }
    }
}
