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
class StockCountTestClockConfiguration {
    @Bean @Primary fun transferClock(): Clock = StockCountIntegrationTest.clock
}

@Testcontainers
@Import(StockCountTestClockConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["server.address=127.0.0.1",
    "spring.profiles.active=test", "dlt.topics=false", "outbox.publisher.enabled=false",
    "spring.kafka.listener.auto-startup=false", "inventory.reservations.expiry-enabled=false"])
class StockCountIntegrationTest {
    @Autowired lateinit var service: StockCountService
    @Autowired lateinit var inventory: InventoryService
    @Autowired lateinit var reservation: ReservationService
    @Autowired lateinit var fulfillment: FulfillmentService
    @Autowired lateinit var transfer: TransferService
    @Autowired lateinit var skus: InventorySkuRepository
    @Autowired lateinit var balances: StockBalanceRepository
    @Autowired lateinit var ledger: StockLedgerRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var jwt: JwtTokenProvider
    @SpyBean lateinit var mapper: ObjectMapper
    @LocalServerPort var port: Int = 0
    private val actor = UUID.randomUUID()
    private val action = StockCountAction("수량 확인 후 승인")
    private fun login(authority: String = "ROLE_ADMIN") {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(actor, null,
            listOf(SimpleGrantedAuthority(authority)))
    }
    @BeforeEach fun clean() {
        Mockito.reset(mapper); clock.time.set(Instant.now())
        jdbc.execute("TRUNCATE inventory_warehouse, inventory_supplier, inventory_sku, outbox_event, processed_event CASCADE")
        login()
    }
    @AfterEach fun clear() { SecurityContextHolder.clearContext() }
    private data class Fixture(val sku: UUID, val warehouse: UUID, val receipt: ReceiptResponse) {
        val lot get() = receipt.lines.single().lot.id
        fun request() = StockCountRequest(warehouse, lot, "정기 실사")
    }
    private fun fixture(quantity: Long = 10, quality: LotQuality = LotQuality.GOOD): Fixture {
        val sku = UUID.randomUUID(); skus.saveAndFlush(InventorySku(sku, "실사 상품"))
        val w = inventory.createWarehouse(WarehouseRequest(UUID.randomUUID().toString(), "중앙", "서울"))
        val s = inventory.createSupplier(SupplierRequest(UUID.randomUUID().toString(), "공급사"))
        val po = inventory.createPurchaseOrder(UUID.randomUUID(), PurchaseOrderRequest(s.id,w.id,listOf(PurchaseLineRequest(sku,quantity,1))))
        val receipt = inventory.receive(UUID.randomUUID(), InboundRequest(po.order.id,"실사용 입고",listOf(
            InboundLineRequest(po.lines.single().id,"COUNT-LOT",LocalDate.now(clock).plusDays(3),quality,quantity))))
        return Fixture(sku,w.id,receipt)
    }
    private fun measured(f: Fixture, quantity: Long = 8): UUID {
        val id = UUID.randomUUID(); service.start(id,f.request()); service.measure(id,StockCountMeasurement(quantity,"실물 측정")); return id
    }
    private fun reserve(f: Fixture, quantity: Long = 2, allocate: Boolean = false): Pair<UUID,ReservationRequest> {
        val id = UUID.randomUUID(); val body = ReservationRequest(UUID.randomUUID(),UUID.randomUUID(),f.sku,quantity,clock.instant().plusSeconds(600))
        login("INVENTORY_SERVICE"); reservation.reserve(id,body); if (allocate) reservation.allocate(id,body); login(); return id to body
    }
    private fun expectConflict(action: () -> Any) {
        assertEquals(409,assertThrows(ResponseStatusException::class.java) { action() }.statusCode.value())
    }
    private fun snapshot() = listOf("inventory_stock_count","inventory_reconciliation","inventory_stock_balance","inventory_stock_ledger","outbox_event")
        .associateWith { jdbc.queryForList("SELECT * FROM $it ORDER BY id") }
    private fun consistent(f: Fixture) {
        val report = service.reconcile(UUID.randomUUID(),ReconciliationRequest(f.sku,reason="일관성 확인"))
        assertEquals(0,report.report.mismatchCount); assertTrue(report.rows.all { it["matched"].asBoolean() })
    }
    private fun parallel(actions: List<() -> Any>): List<Any> {
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(actions.size)
        return try { val futures = actions.map { task -> pool.submit<Any> {
            login(); try { assertTrue(start.await(5,TimeUnit.SECONDS)); task() } finally { SecurityContextHolder.clearContext() }
        } }; start.countDown(); futures.map { it.get(25,TimeUnit.SECONDS) } } finally { pool.shutdownNow() }
    }
    private fun attempt(action: () -> Any): Any = try { action() } catch(e: ResponseStatusException) { assertEquals(409,e.statusCode.value()); "CONFLICT" }
    private fun http(method: String,path: String,body: Any? = null,expected: Int = 200,role: UserRole? = UserRole.ADMIN,bearer: String? = null): JsonNode {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/inventory$path")).timeout(Duration.ofSeconds(15))
        val token = bearer ?: role?.let { jwt.createAccessToken(actor,it) }; if(token != null) request.header("Authorization","Bearer $token")
        request.header("Content-Type","application/json").method(method,if(body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(if(body is String) body else mapper.writeValueAsString(body)))
        val response = HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString())
        assertEquals(expected,response.statusCode(),response.body())
        return if(response.body().isBlank() || response.body().trimStart().startsWith("<")) mapper.createObjectNode() else mapper.readTree(response.body())
    }

    @Test fun `실제 HTTP 기준 측정 승인과 같은 요청 재전송은 원장 Outbox와 한 번만 반영한다`() {
        val f = fixture(); val reportId = UUID.randomUUID()
        http("PUT","/reconciliations/$reportId",ReconciliationRequest(f.sku,reason="실사 전 대사"))
        val id = UUID.randomUUID(); val request = f.request().copy(reconciliationId=reportId)
        val initial = http("PUT","/counts/$id",request)
        assertEquals(10L,initial["baseOnHand"].asLong()); assertEquals(actor.toString(),initial["startedBy"].asText())
        assertFalse(initial.has("requestContent")); assertEquals(10L,balances.findAll().single().onHand)
        http("PUT","/counts/$id/measurement",StockCountMeasurement(8,"두 개 분실"))
        http("PUT","/counts/$id/approval",action)
        val before = snapshot()
        http("PUT","/counts/$id",request); http("PUT","/counts/$id/measurement",StockCountMeasurement(8,"두 개 분실"))
        val applied = http("PUT","/counts/$id/approval",action)
        assertEquals(before,snapshot()); assertEquals("APPLIED",applied["status"].asText()); assertEquals(-2L,applied["adjustment"].asLong())
        assertEquals(8L,balances.findAll().single().onHand)
        val movement = ledger.findAll().single { it.documentId==id }; assertEquals(actor,movement.actorId); assertEquals(-2L,movement.onHandDelta)
        assertEquals(action.reason,movement.reason); assertEquals(1,outbox.findAll().count { it.eventType=="STOCK_COUNT_APPLIED" })
        http("PUT","/counts/$id/approval",StockCountAction("다른 승인"),409)
        http("PUT","/counts/$id/cancellation",action,409)
        http("PUT","/counts/$id/measurement",StockCountMeasurement(9,"다른 측정"),409)
        consistent(f)
    }

    @ParameterizedTest
    @ValueSource(strings=["missing", "null", "1.9", "1.0", "1e3", "1e-400", "\"8\"", "true", "[]", "{}",
        "-1", "9223372036854775808", "-9223372036854775809"])
    fun `실제 HTTP의 누락 null 소수와 잘못된 실사 수량은 아무것도 변경하지 않는다`(quantity: String) {
        val f=fixture(); val id=UUID.randomUUID(); http("PUT","/counts/$id",f.request())
        val before=snapshot()
        val body=if(quantity=="missing") """{"reason":"입력 경계 확인"}"""
            else """{"reason":"입력 경계 확인","countedQuantity":$quantity}"""
        http("PUT","/counts/$id/measurement",body,400)
        assertEquals(StockCountStatus.OPEN,service.get(id).status); assertNull(service.get(id).countedQuantity)
        http("PUT","/counts/$id/approval",action,409)
        assertEquals(before,snapshot())
    }

    @ParameterizedTest @ValueSource(longs=[0,8,Long.MAX_VALUE])
    fun `실제 HTTP의 명시적 영 양수 Long 최댓값은 정확하게 한 번 반영한다`(quantity: Long) {
        val f=fixture(); val id=UUID.randomUUID(); http("PUT","/counts/$id",f.request())
        val body="""{"reason":"실측 확인","countedQuantity":$quantity}"""
        val measured=http("PUT","/counts/$id/measurement",body)
        assertEquals("MEASURED",measured["status"].asText()); assertEquals(quantity,measured["countedQuantity"].asLong())
        val applied=http("PUT","/counts/$id/approval",action)
        assertEquals("APPLIED",applied["status"].asText()); assertEquals(quantity-10,applied["adjustment"].asLong())
        assertEquals(quantity,balances.findAll().single().onHand)
        assertEquals(quantity-10,ledger.findAll().single { it.documentId==id }.onHandDelta)
        val event=outbox.findAll().single { it.eventType=="STOCK_COUNT_APPLIED" }
        assertEquals(quantity,mapper.readTree(event.payload)["count"]["countedQuantity"].asLong())
        val before=snapshot()
        http("PUT","/counts/$id/measurement",body); http("PUT","/counts/$id/approval",action)
        assertEquals(before,snapshot()); consistent(f)
    }

    @Test fun `차이 없는 승인과 Long 양끝의 조정도 수량과 원장을 보존한다`() {
        val f = fixture(); val zero = measured(f,10); service.approve(zero,action)
        assertEquals(0L,ledger.findAll().single { it.documentId==zero }.onHandDelta)
        for (quantity in listOf(Long.MAX_VALUE,0L,Long.MAX_VALUE)) {
            val id = measured(f,quantity); service.approve(id,action); assertEquals(quantity,balances.findAll().single().onHand); consistent(f)
        }
    }

    @Test fun `불량 실사는 판매불가를 함께 조정하며 검수 중 재고는 거부한다`() {
        val f = fixture(quality=LotQuality.DAMAGED); service.approve(measured(f,3),action)
        val b = balances.findAll().single(); assertEquals(3L,b.onHand); assertEquals(3L,b.blocked)
        assertEquals(0L,inventory.stockSummary(f.sku,null).single().available); consistent(f)
        val pending = fixture(quality=LotQuality.INSPECTION)
        expectConflict { service.start(UUID.randomUUID(),pending.request()) }
    }

    @Test fun `활성 예약 할당 피킹 이동 확보 중에는 실사 기준을 잡지 않는다`() {
        for (phase in listOf("reservation","allocated","picking","transfer")) {
            val f = fixture()
            if (phase=="transfer") {
                val destination = inventory.createWarehouse(WarehouseRequest(UUID.randomUUID().toString(),"도심","서울"))
                transfer.create(UUID.randomUUID(),TransferRequest(f.warehouse,destination.id,clock.instant().plusSeconds(3600),"이동",listOf(TransferQuantity(f.lot,2))))
            } else {
                val (id,body) = reserve(f,allocate=phase!="reservation")
                if (phase=="picking") {
                    fulfillment.create(com.eventfulcommerce.common.DirectFulfillmentPayload(body.orderAttemptId,actor,UUID.randomUUID(),body.orderItemId,id,f.sku,f.warehouse,2))
                    fulfillment.act(id,"pick",OutboundActionRequest("피킹",listOf(LotQuantity(f.lot,2))))
                }
            }
            expectConflict { service.start(UUID.randomUUID(),f.request()) }; consistent(f)
        }
    }

    @Test fun `실사 중 예약 후 해제와 이동 왕복은 최종 수량이 같아도 기준을 무효화한다`() {
        for (phase in listOf("reservation","transfer")) {
            val f = fixture(); val id = measured(f)
            if(phase=="reservation") {
                val (reservationId,body) = reserve(f); login("INVENTORY_SERVICE"); reservation.release(reservationId,body); login()
            } else {
                val destination = inventory.createWarehouse(WarehouseRequest(UUID.randomUUID().toString(),"도심","서울"))
                val t = UUID.randomUUID(); transfer.create(t,TransferRequest(f.warehouse,destination.id,clock.instant().plusSeconds(3600),"이동",listOf(TransferQuantity(f.lot,2))))
                transfer.cancel(t,TransferActionRequest("이동 취소"))
            }
            assertEquals(10L,balances.findAll().first { it.skuId==f.sku }.onHand)
            expectConflict { service.approve(id,action) }; assertEquals(StockCountStatus.MEASURED,service.get(id).status)
            service.cancel(id,StockCountAction("기준 변경으로 재실사")); service.approve(measured(f),action); consistent(f)
        }
    }

    @Test fun `같은 ID 동시 시작과 동시 승인은 문서 원장 이벤트를 중복하지 않는다`() {
        val f = fixture(); val id = UUID.randomUUID()
        assertEquals(1,parallel(List(6) { { service.start(id,f.request()) } }).map { (it as StockCount).startedAt }.distinct().size)
        service.measure(id,StockCountMeasurement(7,"실측"))
        parallel(List(6) { { service.approve(id,action) } })
        assertEquals(7L,balances.findAll().single().onHand); assertEquals(1,ledger.findAll().count { it.documentId==id }); consistent(f)
    }

    @Test fun `서로 다른 실사 기준의 승인 경합은 오래된 측정을 적용하지 않는다`() {
        val f = fixture(); val first = measured(f,8); val second = measured(f,6)
        val results = parallel(listOf({ attempt { service.approve(first,action) } },{ attempt { service.approve(second,action) } }))
        assertEquals(1,results.count { it is StockCount }); assertEquals(1,results.count { it=="CONFLICT" })
        assertTrue(balances.findAll().single().onHand in listOf(6L,8L)); consistent(f)
    }

    @Test fun `실사 승인과 마지막 재고 예약 경합은 초과 확보를 만들지 않는다`() {
        repeat(4) {
            val f = fixture(); val id = measured(f,1)
            parallel(listOf({ attempt { service.approve(id,action) } },{ reserve(f,8) }))
            val b = balances.findAll().single { it.skuId==f.sku }
            assertTrue(b.reserved<=b.onHand); assertTrue(b.onHand==1L || b.onHand==10L); consistent(f)
        }
    }

    @ParameterizedTest @ValueSource(booleans=[false,true])
    fun `DB 실패와 checked 직렬화 실패는 승인 전체를 롤백한다`(checked: Boolean) {
        val f = fixture(); val id = measured(f); val before=snapshot()
        if(checked) Mockito.doAnswer { invocation ->
            if(invocation.arguments[0] is Map<*,*>) throw object: JsonProcessingException("실사 이벤트 실패") {}
            invocation.callRealMethod()
        }.`when`(mapper).writeValueAsString(ArgumentMatchers.any())
        else jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT s8_fail CHECK (aggregate_type<>'INVENTORY_COUNT') NOT VALID")
        try {
            if(checked) assertThrows(JsonProcessingException::class.java) { service.approve(id,action) }
            else assertThrows(DataIntegrityViolationException::class.java) { service.approve(id,action) }
        } finally { Mockito.reset(mapper); if(!checked) jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT s8_fail") }
        assertEquals(before,snapshot()); service.approve(id,action); consistent(f)
    }

    @Test fun `취소 실사는 늦은 측정과 승인으로 되살아나지 않는다`() {
        val f=fixture(); val id=UUID.randomUUID(); service.start(id,f.request()); service.cancel(id,action)
        val before=snapshot(); service.cancel(id,action); service.start(id,f.request()); assertEquals(before,snapshot())
        expectConflict { service.measure(id,StockCountMeasurement(3,"늦은 실측")) }; expectConflict { service.approve(id,action) }
        expectConflict { service.start(id,f.request().copy(reason="다른 기준")) }; consistent(f)
    }

    @Test fun `원장 대사는 다섯 차원 불일치를 기록하고 재고를 바꾸지 않으며 후속 추적한다`() {
        val f=fixture(); val id=UUID.randomUUID(); val request=ReconciliationRequest(f.sku,reason="차이 조사")
        jdbc.update("UPDATE inventory_stock_balance SET on_hand=12,reserved=1,allocated=1,blocked=1,transfer_held=1 WHERE sku_id=?",f.sku)
        val before=balances.findAll().single().version
        val report=service.reconcile(id,request)
        assertEquals(1,report.report.mismatchCount); assertEquals(12L,report.rows[0]["onHand"].asLong()); assertEquals(10L,report.rows[0]["ledgerOnHand"].asLong())
        for(key in listOf("ledgerReserved","ledgerAllocated","ledgerBlocked","ledgerTransferHeld")) assertEquals(0L,report.rows[0][key].asLong())
        assertEquals(before,balances.findAll().single().version)
        jdbc.update("UPDATE inventory_stock_balance SET on_hand=10,reserved=0,allocated=0,blocked=0,transfer_held=0 WHERE sku_id=?",f.sku)
        assertEquals(1,service.reconcile(id,request).report.mismatchCount)
        val follow=service.reconcile(UUID.randomUUID(),request.copy(previousReportId=id))
        assertEquals(0,follow.report.mismatchCount); assertEquals(id,follow.report.previousReportId)
        val c=service.start(UUID.randomUUID(),f.request().copy(reconciliationId=id)); assertEquals(id,c.reconciliationId)
        val other=fixture(); expectConflict { service.start(UUID.randomUUID(),other.request().copy(reconciliationId=id)) }
        expectConflict { service.reconcile(UUID.randomUUID(),ReconciliationRequest(other.sku,reason="다른 범위",previousReportId=id)) }
    }

    @Test fun `잔량 행이 없는 원장과 창고별 페이지도 누락 없이 대사한다`() {
        val f=fixture(); jdbc.update("DELETE FROM inventory_stock_balance WHERE sku_id=?",f.sku)
        val report=service.reconcile(UUID.randomUUID(),ReconciliationRequest(f.sku,f.warehouse,"행 누락 조사",size=1))
        assertEquals(1,report.report.mismatchCount); assertTrue(report.rows[0]["balanceId"].isNull)
        assertEquals(10L,report.rows[0]["ledgerOnHand"].asLong()); assertFalse(report.report.hasNext)
        assertTrue(service.reconcile(UUID.randomUUID(),ReconciliationRequest(f.sku,f.warehouse,"다음 페이지",page=1,size=1)).rows.isEmpty)
    }

    @Test fun `다른 SKU의 같은 대사 ID 경합은 최초 보고서를 덮어쓰지 않는다`() {
        val first=fixture(); val second=fixture(); val id=UUID.randomUUID()
        val results=parallel(listOf({ attempt { service.reconcile(id,ReconciliationRequest(first.sku,reason="첫 범위")) } },
            { attempt { service.reconcile(id,ReconciliationRequest(second.sku,reason="두 번째 범위")) } }))
        assertEquals(1,results.count { it is ReconciliationResponse }); assertEquals(1,results.count { it=="CONFLICT" })
        assertTrue(service.report(id).report.skuId in listOf(first.sku,second.sku))
    }

    @Test fun `미완료 운영 조회는 창고 필터 페이지와 관리자 권한을 지킨다`() {
        val f=fixture(); val id=measured(f); reserve(f)
        assertEquals(1L,http("GET","/operations/reservations?warehouseId=${f.warehouse}")["totalElements"].asLong())
        assertEquals(1L,http("GET","/operations/purchase-orders")["totalElements"].asLong())
        assertEquals(1L,http("GET","/operations/receipts")["totalElements"].asLong())
        assertEquals(0L,http("GET","/operations/outbounds")["totalElements"].asLong())
        assertEquals(1L,http("GET","/counts?incompleteOnly=true&size=1")["totalElements"].asLong())
        assertEquals(0,http("GET","/counts?incompleteOnly=true&page=1&size=1")["content"].size())
        service.cancel(id,action); assertEquals(0L,http("GET","/counts?incompleteOnly=true")["totalElements"].asLong())
        http("GET","/operations/reservations?warehouseId=${UUID.randomUUID()}").also { assertEquals(0L,it["totalElements"].asLong()) }
    }

    @Test fun `실제 HTTP와 서비스 프록시는 사용자 판매자 내부 토큰과 위조 입력을 거부한다`() {
        val f=fixture(); val id=UUID.randomUUID()
        for(role in listOf(UserRole.USER,UserRole.SELLER)) {
            http("PUT","/counts/$id",f.request(),403,role)
            http("GET","/operations/reservations",expected=403,role=role)
            http("PUT","/reconciliations/${UUID.randomUUID()}",ReconciliationRequest(f.sku,reason="권한 우회"),403,role)
        }
        http("PUT","/counts/$id",f.request(),401,role=null)
        http("PUT","/counts/$id",f.request(),401,role=null,bearer=internal)
        http("PUT","/counts/$id",f.request(),401,bearer=jwt.createRefreshToken(actor,UserRole.ADMIN))
        http("PUT","/counts/$id",f.request().copy(reason=" "),400)
        http("PUT","/counts/$id",f.request())
        http("PUT","/counts/$id/measurement",StockCountMeasurement(-1,"음수"),400)
        http("GET","/counts?page=-1",expected=400); http("GET","/operations/outbounds?size=201",expected=400)
        http("PUT","/reconciliations/${UUID.randomUUID()}",ReconciliationRequest(f.sku,reason="오류",size=201),400)
        login("ROLE_USER"); assertThrows(AccessDeniedException::class.java) { service.get(id) }; login()
        for(statement in listOf("UPDATE inventory_stock_count SET base_version=-1 WHERE id='$id'",
            "UPDATE inventory_stock_count SET status='APPLIED' WHERE id='$id'","UPDATE inventory_stock_count SET counted_quantity=-1 WHERE id='$id'")) {
            assertThrows(DataIntegrityViolationException::class.java) { jdbc.execute(statement) }
        }
    }

    @Test fun `관리자 화면 진입은 정적 GET만 허용하고 데이터 API는 공개하지 않는다`() {
        val client=HttpClient.newHttpClient()
        for(path in listOf("/admin/index.html","/admin/app.js","/admin/styles.css")) {
            val response=client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/inventory$path")).GET().build(),HttpResponse.BodyHandlers.ofString())
            assertEquals(200,response.statusCode()); assertTrue(response.body().isNotBlank())
            http("POST",path,expected=401,role=null)
        }
        http("GET","/stock?skuId=${UUID.randomUUID()}",expected=401,role=null)
        http("GET","/admin/unknown",expected=401,role=null)
    }

    @Test fun `새 애플리케이션은 측정 문서와 대사 기록을 복구하고 같은 승인으로 종결한다`() {
        val f=fixture(); val id=measured(f); val reportId=UUID.randomUUID()
        service.reconcile(reportId,ReconciliationRequest(f.sku,reason="재기동 전 대사"))
        SpringApplicationBuilder(InventoryServiceApplication::class.java,StockCountTestClockConfiguration::class.java)
            .run(*mapOf("server.port" to "0","server.address" to "127.0.0.1","spring.profiles.active" to "test",
                "spring.datasource.url" to postgres.jdbcUrl,"spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password,"jwt.secret" to secret,"inventory.service-token" to internal,
                "dlt.topics" to "false","outbox.publisher.enabled" to "false","spring.kafka.listener.auto-startup" to "false",
                "inventory.reservations.expiry-enabled" to "false").map { (key,value) -> "--$key=$value" }.toTypedArray()).use { context ->
                val recovered=context.getBean(StockCountService::class.java); assertNotSame(service,recovered)
                assertEquals(StockCountStatus.MEASURED,recovered.get(id).status); assertEquals(reportId,recovered.report(reportId).report.id)
                recovered.approve(id,action); recovered.approve(id,action)
            }
        assertEquals(8L,balances.findAll().single().onHand); consistent(f)
    }

    @ParameterizedTest @ValueSource(strings=["inbound","outbound","arrival"])
    fun `기준 이후 실제 입고 출고 도착은 오래된 실사 승인을 차단한다`(phase: String) {
        val f=fixture()
        val other=inventory.createWarehouse(WarehouseRequest(UUID.randomUUID().toString(),"도심","서울"))
        fun move(from: UUID,to: UUID) {
            val t=UUID.randomUUID(); transfer.create(t,TransferRequest(from,to,clock.instant().plusSeconds(3600),"이동",listOf(TransferQuantity(f.lot,2))))
            transfer.dispatch(t,TransferActionRequest("출발")); transfer.arrive(t,UUID.randomUUID(),TransferArrivalRequest("도착",listOf(TransferQuantity(f.lot,2))))
        }
        if(phase=="arrival") move(f.warehouse,other.id)
        val id=measured(f,6)
        when(phase) {
            "inbound" -> {
                val original=inventory.purchaseOrder(f.receipt.receipt.purchaseOrderId)
                val po=inventory.createPurchaseOrder(UUID.randomUUID(),PurchaseOrderRequest(original.order.supplierId,f.warehouse,listOf(PurchaseLineRequest(f.sku,3,1))))
                inventory.receive(UUID.randomUUID(),InboundRequest(po.order.id,"추가 입고",listOf(InboundLineRequest(po.lines.single().id,"COUNT-LOT",f.receipt.lines.single().lot.expiresOn,LotQuality.GOOD,3))))
            }
            "outbound" -> {
                val (reservationId,body)=reserve(f,allocate=true)
                fulfillment.create(com.eventfulcommerce.common.DirectFulfillmentPayload(body.orderAttemptId,actor,UUID.randomUUID(),body.orderItemId,reservationId,f.sku,f.warehouse,2))
                for(action in listOf("pick","pack","ship")) fulfillment.act(reservationId,action,OutboundActionRequest("창고 작업",listOf(LotQuantity(f.lot,2))))
            }
            else -> move(other.id,f.warehouse)
        }
        val before=snapshot(); expectConflict { service.approve(id,action) }; assertEquals(before,snapshot()); consistent(f)
    }

    @Test fun `대사 페이지와 창고 필터는 모든 로트와 범위별 후속 보고서를 보존한다`() {
        val f=fixture(); val other=inventory.createWarehouse(WarehouseRequest(UUID.randomUUID().toString(),"도심","서울"))
        val t=UUID.randomUUID(); transfer.create(t,TransferRequest(f.warehouse,other.id,clock.instant().plusSeconds(3600),"이동",listOf(TransferQuantity(f.lot,2))))
        transfer.dispatch(t,TransferActionRequest("출발")); transfer.arrive(t,UUID.randomUUID(),TransferArrivalRequest("도착",listOf(TransferQuantity(f.lot,2))))
        val request=ReconciliationRequest(f.sku,reason="페이지 대사",size=1)
        val first=service.reconcile(UUID.randomUUID(),request)
        val second=service.reconcile(UUID.randomUUID(),request.copy(page=1))
        assertTrue(first.report.hasNext); assertFalse(second.report.hasNext)
        assertEquals(setOf(f.warehouse.toString(),other.id.toString()),setOf(first.rows[0]["warehouseId"].asText(),second.rows[0]["warehouseId"].asText()))
        assertEquals(0,first.report.mismatchCount+second.report.mismatchCount)
        val scoped=service.reconcile(UUID.randomUUID(),request.copy(warehouseId=f.warehouse))
        assertFalse(scoped.report.hasNext); assertEquals(f.warehouse.toString(),scoped.rows[0]["warehouseId"].asText())
        expectConflict { service.reconcile(UUID.randomUUID(),request.copy(page=1,previousReportId=first.report.id)) }
        assertEquals(3L,http("GET","/reconciliations?skuId=${f.sku}")["totalElements"].asLong())
    }

    companion object {
        class MutableClock : Clock() {
            val time = AtomicReference(Instant.now())
            override fun instant(): Instant = time.get()
            override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(),zone)
        }
        val clock=MutableClock()
        private val secret=UUID.randomUUID().toString().repeat(2)
        private val internal=UUID.randomUUID().toString().repeat(2)
        @Container @JvmStatic val postgres=PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @DynamicPropertySource @JvmStatic fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url",postgres::getJdbcUrl); registry.add("spring.datasource.username",postgres::getUsername)
            registry.add("spring.datasource.password",postgres::getPassword); registry.add("jwt.secret") { secret }; registry.add("inventory.service-token") { internal }
        }
    }
}
