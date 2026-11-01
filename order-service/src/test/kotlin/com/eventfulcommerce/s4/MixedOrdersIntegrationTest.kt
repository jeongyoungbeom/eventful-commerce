package com.eventfulcommerce.s4

import com.eventfulcommerce.common.*
import com.eventfulcommerce.common.auth.*
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.inventory.domain.InventorySku
import com.eventfulcommerce.inventory.domain.LotQuality
import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.repository.InventorySkuRepository
import com.eventfulcommerce.inventory.repository.StockBalanceRepository
import com.eventfulcommerce.inventory.service.InventoryService
import com.eventfulcommerce.inventory.service.ReservationService
import com.eventfulcommerce.order.controller.OrderController
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.*
import com.eventfulcommerce.order.dto.OrderResponse
import com.eventfulcommerce.order.exception.*
import com.eventfulcommerce.order.repository.*
import com.eventfulcommerce.order.request.*
import com.eventfulcommerce.order.scheduler.MixedOrderRecoveryScheduler
import com.eventfulcommerce.order.service.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*
import org.redisson.spring.starter.RedissonAutoConfigurationV2
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.*
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@Testcontainers
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.show-sql=false"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [S4OrderConfiguration::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MixedOrdersIntegrationTest {
    @Autowired lateinit var service: MixedOrdersService
    @Autowired lateinit var state: MixedOrderStateService
    @Autowired lateinit var orders: OrdersRepository
    @Autowired lateinit var attempts: MixedOrderAttemptRepository
    @Autowired lateinit var products: ProductReadModelRepository
    @Autowired lateinit var claims: OrderRequestIdempotencyRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var inventory: InventoryReservationService
    @Autowired lateinit var redis: StringRedisTemplate
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var legacy: OrdersService
    @Autowired lateinit var source: DataSource
    @Autowired lateinit var manager: PlatformTransactionManager
    private val user = UUID.randomUUID()
    private val jdbc get() = JdbcTemplate(source)

    @BeforeEach fun clean() {
        testClock.time = Instant.now()
        mode = "normal"; onPut = null; calls.set(0); reset(legacy); finishBodies = CountDownLatch(1)
        attempts.deleteAll(); claims.deleteAll(); outbox.deleteAll(); orders.deleteAll(); products.deleteAll()
        redis.execute { it.serverCommands().flushAll(); null }
        remoteContext.getBean(JdbcTemplate::class.java).execute(
            "TRUNCATE inventory_reservation, inventory_warehouse, inventory_supplier, inventory_sku, outbox_event, processed_event CASCADE")
    }
    @AfterEach fun clear() { finishBodies.countDown(); SecurityContextHolder.clearContext(); mode = "normal"; onPut = null }

    private fun marketplace(stock: Int = 10, price: Long = 12000): UUID {
        val id = UUID.randomUUID()
        products.saveAndFlush(ProductReadModel(id, UUID.randomUUID(), "입점", price, stock, "테스트"))
        inventory.initializeStock(id.toString(), stock)
        return id
    }
    private fun direct(stock: Long = 100, price: Long = 10000): UUID {
        val id = UUID.randomUUID()
        products.saveAndFlush(ProductReadModel(id, SalesType.OFFICIAL_SELLER_ID, "직매입", price, 0, "테스트", salesType = SalesType.DIRECT_PURCHASE))
        remoteContext.getBean(InventorySkuRepository::class.java).saveAndFlush(InventorySku(id, "직매입"))
        if (stock > 0) {
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(user, null,
                listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
            val inv = remoteContext.getBean(InventoryService::class.java)
            val w = inv.createWarehouse(WarehouseRequest(UUID.randomUUID().toString(), "창고", "서울"))
            val supplier = inv.createSupplier(SupplierRequest(UUID.randomUUID().toString(), "공급사"))
            val po = inv.createPurchaseOrder(UUID.randomUUID(), PurchaseOrderRequest(supplier.id, w.id,
                listOf(PurchaseLineRequest(id, stock, 1))))
            inv.receive(UUID.randomUUID(), InboundRequest(po.order.id, "S4 실제 입고", listOf(
                InboundLineRequest(po.lines.single().id, UUID.randomUUID().toString(), LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1), LotQuality.GOOD, stock))))
            SecurityContextHolder.clearContext()
        }
        return id
    }
    private fun request(vararg items: Pair<UUID, Int>) = OrdersRequest(items.map { OrderItemRequest(it.first, it.second) })
    private fun create(request: OrdersRequest, key: String = UUID.randomUUID().toString()) = service.orders(request, user, key)
    private fun rows() = remoteContext.getBean(JdbcTemplate::class.java)
    private fun reserved(sku: UUID) = remoteContext.getBean(StockBalanceRepository::class.java).findAll().filter { it.skuId == sku }.sumOf { it.reserved }
    private fun assertNoPayment() = assertFalse(outbox.findAll().any { it.eventType == "ORDER_RESERVED" })

    @Test fun `네 품목 중 세 성공 한 품절은 각 재고 경로와 배송비 한 번을 보존하고 확인 전 차단하고 확인 후 한 결제 원본을 만든다`() {
        val market = marketplace(); val shampoo = direct(); val cream = direct(20, 15000); val soldout = direct(0)
        val response = create(request(market to 1, shampoo to 2, cream to 1, soldout to 1))
        assertEquals(OrdersStatus.ORDER_AWAITING_CONFIRMATION, response.status)
        assertEquals(3, response.sellerOrders.sumOf { it.items.size })
        assertEquals(listOf(soldout), response.failedItems.map { it.productId })
        assertEquals(47000, response.totalItemAmount); assertEquals(3000, response.totalDeliveryFee)
        assertEquals(50000, response.totalPaymentAmount); assertTrue(response.confirmationRequired)
        assertEquals(9, inventory.getAvailableStock(market.toString()))
        assertEquals(2, reserved(shampoo)); assertEquals(1, reserved(cream))
        assertNull(redis.opsForValue().get("{product:$shampoo}:stock"))
        assertEquals(1, response.sellerOrders.filter { it.salesType == SalesType.DIRECT_PURCHASE }.size)
        assertEquals(3000, response.sellerOrders.flatMap { it.items }.sumOf { it.deliveryFeeAllocation })
        assertEquals(0, response.sellerOrders.single { it.salesType == SalesType.DIRECT_PURCHASE }.settlementAmount)
        assertNoPayment()
        val next = attempts.findById(response.orderId!!).orElseThrow().nextRecoveryAt
        service.decorate(response, user)
        assertEquals(next, attempts.findById(response.orderId).orElseThrow().nextRecoveryAt)
        val confirmed = service.confirm(response.orderId!!, user, OrderConfirmationRequest(response.compositionVersion!!, 50000))
        assertEquals(confirmed.compositionVersion, confirmed.confirmedVersion)
        assertFalse(confirmed.confirmationRequired); assertTrue(confirmed.paymentReady)
        assertEquals(1, outbox.findAll().count { it.eventType == "ORDER_RESERVED" })
        assertEquals(confirmed.confirmedVersion, service.confirm(response.orderId, user,
            OrderConfirmationRequest(confirmed.compositionVersion!!, 50000)).confirmedVersion)
        assertEquals(1, outbox.findAll().count { it.eventType == "INVENTORY_STOCK_ADJUSTED" })
    }

    @Test fun `요청 수량 일부 부족과 전부 실패는 수량을 바꾸지 않고 결제를 생성하지 않는다`() {
        val sku = direct(1)
        val r = create(request(sku to 2))
        assertEquals(OrdersStatus.ORDER_FAILED, r.status)
        assertEquals(2, r.failedItems.single().requestedQuantity)
        assertEquals(0, reserved(sku)); assertTrue(r.sellerOrders.isEmpty()); assertEquals(0, r.totalPaymentAmount)
        assertNoPayment()
        assertThrows(OrderCompositionConflictException::class.java) {
            service.confirm(r.orderId!!, user, OrderConfirmationRequest(r.compositionVersion!!, 0)) }
    }

    @Test fun `예약 성공 응답 유실은 품절로 분류하지 않고 같은 영속 ID 조회로 복구한다`() {
        val sku = direct(); mode = "lost-response"
        val req = request(sku to 2); val key = UUID.randomUUID().toString()
        val r = create(req, key)
        assertEquals(OrdersStatus.ORDER_PROCESSING, r.status); assertEquals(1, r.pendingItems.size)
        assertTrue(r.failedItems.isEmpty()); assertEquals(2, reserved(sku)); assertFalse(r.paymentReady)
        assertThrows(OrderCompositionConflictException::class.java) {
            state.confirm(r.orderId!!, user, OrderConfirmationRequest(r.compositionVersion!!, r.totalPaymentAmount)) }
        mode = "normal"
        val recovered = create(req, key)
        assertEquals(r.orderId, recovered.orderId); assertTrue(recovered.pendingItems.isEmpty())
        assertEquals(OrdersStatus.ORDER_AWAITING_CONFIRMATION, recovered.status)
        assertEquals(1, rows().queryForObject("select count(*) from inventory_reservation", Int::class.java))
        assertEquals(2, reserved(sku)); assertNoPayment()
    }

    @Test fun `실제 HTTP 응답 시간 제한은 미확인으로 남고 다음 조회에서 복구한다`() {
        val sku = direct(); mode = "timeout"
        val r = create(request(sku to 1))
        assertEquals(OrdersStatus.ORDER_PROCESSING, r.status)
        assertTrue(r.failedItems.isEmpty()); assertEquals(1, reserved(sku))
        mode = "normal"
        assertEquals(OrdersStatus.ORDER_AWAITING_CONFIRMATION, service.getOrder(r.orderId!!, user).status)
    }

    @Test fun `본문 수신 중단은 시간 내 미확인으로 반환하고 같은 예약 ID로 복구한다`() {
        val sku = direct(); mode = "body-timeout"
        val req = request(sku to 2); val key = UUID.randomUUID().toString()
        val r = assertTimeoutPreemptively<OrderResponse>(Duration.ofSeconds(10)) { create(req, key) }
        assertEquals(OrdersStatus.ORDER_PROCESSING, r.status)
        assertEquals(1, r.pendingItems.size); assertTrue(r.failedItems.isEmpty()); assertFalse(r.paymentReady)
        assertEquals(2, reserved(sku)); assertNoPayment()
        val original = state.snapshot(r.orderId!!).items.single()
        mode = "normal"; finishBodies.countDown()
        val recovered = create(req, key)
        assertEquals(r.orderId, recovered.orderId)
        assertEquals(OrdersStatus.ORDER_AWAITING_CONFIRMATION, recovered.status)
        assertEquals(original.reservationId, state.snapshot(r.orderId).items.single().reservationId)
        assertEquals(1, rows().queryForObject("select count(*) from inventory_reservation", Int::class.java))
        assertEquals(2, reserved(sku)); assertNoPayment()
    }

    @Test fun `해제 응답 본문 중단은 영속 대기로 반환하고 다음 조회에서 종결한다`() {
        val sku = direct(); val r = create(request(sku to 2)); mode = "body-timeout"
        val result = assertTimeoutPreemptively<OrderCancellationOutcome>(Duration.ofSeconds(10)) { service.cancel(r.orderId!!, user) }
        assertEquals(OrderCancellationOutcome.PENDING, result)
        assertNotNull(attempts.findById(r.orderId!!).orElseThrow().nextRecoveryAt)
        assertEquals(0, reserved(sku)); assertNoPayment()
        mode = "normal"; finishBodies.countDown()
        val recovered = service.getOrder(r.orderId, user)
        assertEquals(OrdersStatus.ORDER_CANCELED, recovered.status)
        assertTrue(recovered.pendingItems.isEmpty())
        assertNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
        assertEquals(0, reserved(sku)); assertNoPayment()
    }

    @Test fun `반복 주문과 내용 충돌은 기존 예약 ID 원본과 최초 가격을 보존한다`() {
        val sku = direct(); val key = UUID.randomUUID().toString(); val req = request(sku to 2)
        val r = create(req, key)
        products.findById(sku).orElseThrow().let { it.price = 99999; products.saveAndFlush(it) }
        val replay = create(req, key)
        assertEquals(r.orderId, replay.orderId); assertEquals(r.sellerOrders, replay.sellerOrders)
        assertEquals(r.compositionVersion, replay.compositionVersion)
        assertThrows(OrderIdempotencyConflictException::class.java) { create(request(sku to 3), key) }
        assertEquals(2, reserved(sku))
    }

    @Test fun `원격 호출 전에 의도와 원본 ID가 커밋되고 주문 행 잠금을 보유하지 않는다`() {
        val sku = direct()
        onPut = { body ->
            val id = UUID.fromString(mapper.readTree(body).required("orderAttemptId").asText())
            TransactionTemplate(manager).execute {
                jdbc.execute("SET LOCAL lock_timeout = '500ms'")
                assertNotNull(orders.findByIdForUpdate(id))
                val saved = attempts.findById(id).orElseThrow()
                assertTrue(saved.itemsJson.contains(mapper.readTree(body).required("orderItemId").asText()))
                assertTrue(claims.findAll().any { it.orderId == id })
            }
        }
        val r = create(request(sku to 1))
        assertEquals(OrdersStatus.ORDER_AWAITING_CONFIRMATION, r.status)
    }

    @Test fun `취소 후 늦은 PUT은 선행 해제 종결을 되살리지 않는다`() {
        val sku = direct(); val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        onPut = { entered.countDown(); check(proceed.await(10, TimeUnit.SECONDS)) }
        try {
            val future = pool.submit<OrderResponse> { create(request(sku to 2)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val id = attempts.findAll().single().id
            assertEquals(OrderCancellationOutcome.CANCELED, service.cancel(id, user))
            proceed.countDown()
            assertEquals(OrdersStatus.ORDER_CANCELED, future.get(10, TimeUnit.SECONDS).status)
            assertEquals(0, reserved(sku))
            assertEquals("RELEASED", rows().queryForObject("select status from inventory_reservation", String::class.java))
        } finally { proceed.countDown(); pool.shutdownNow() }
    }

    @Test fun `해제 실패는 영속 대기로 남고 새 애플리케이션 컨텍스트와 스케줄러가 같은 DB에서 복구한다`() {
        val market = marketplace(); val sku = direct()
        val r = create(request(market to 1, sku to 2)); mode = "release-failure"; calls.set(0)
        assertEquals(OrderCancellationOutcome.PENDING, service.cancel(r.orderId!!, user))
        assertEquals(3, calls.get()); assertEquals(2, reserved(sku))
        assertEquals(10, inventory.getAvailableStock(market.toString()))
        assertNotNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
        mode = "normal"
        jdbc.update("update mixed_order_attempt set next_recovery_at = current_timestamp where id = ?", r.orderId)
        SpringApplicationBuilder(S4RecoveryConfiguration::class.java).run(
            "--spring.config.location=optional:classpath:/s4-empty.yml", "--spring.main.web-application-type=none",
            "--spring.datasource.url=${postgres.jdbcUrl}", "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}", "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.flyway.enabled=false", "--logging.level.root=WARN").use { context ->
            MixedOrderRecoveryScheduler(context.getBean(MixedOrderAttemptRepository::class.java),
                context.getBean(MixedOrdersService::class.java)).recover()
        }
        val after = state.snapshot(r.orderId, user).response
        assertEquals(OrdersStatus.ORDER_CANCELED, after.status); assertTrue(after.pendingItems.isEmpty())
        assertEquals(0, reserved(sku)); assertNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
        assertEquals(2, outbox.findAll().count { it.eventType == "INVENTORY_STOCK_ADJUSTED" })
    }

    @Test fun `고객 확인 전 원격 상태 변경은 구성 버전을 바꾸고 새 확인을 요구한다`() {
        val first = direct(); val second = direct()
        val r = create(request(first to 1, second to 1))
        val item = state.snapshot(r.orderId!!).items.first { it.productId == first }
        InventoryServiceClient(mapper, proxyUrl, token).release(r.orderId, item, r.expiresAt!!)
        val changed = service.getOrder(r.orderId, user)
        assertTrue(changed.compositionVersion!! > r.compositionVersion!!)
        assertNull(changed.confirmedVersion); assertTrue(changed.confirmationRequired)
        assertThrows(OrderCompositionConflictException::class.java) {
            service.confirm(r.orderId, user, OrderConfirmationRequest(r.compositionVersion!!, r.totalPaymentAmount)) }
    }

    @Test fun `만료와 타인 확인과 금액 위조는 거부하고 확인 무효화를 커밋한다`() {
        val sku = direct(); val r = create(request(sku to 1))
        assertThrows(OrderForbiddenException::class.java) {
            service.confirm(r.orderId!!, UUID.randomUUID(), OrderConfirmationRequest(r.compositionVersion!!, r.totalPaymentAmount)) }
        assertThrows(OrderCompositionConflictException::class.java) {
            service.confirm(r.orderId!!, user, OrderConfirmationRequest(r.compositionVersion!!, 1)) }
        testClock.time = testClock.time.plusSeconds(601)
        val expired = service.getOrder(r.orderId!!, user)
        assertEquals(OrdersStatus.ORDER_EXPIRED, expired.status); assertNull(expired.confirmedVersion)
        assertTrue(expired.pendingItems.isEmpty()); assertEquals(0, reserved(sku))
        assertFalse(expired.paymentReady)
        assertThrows(OrderCompositionConflictException::class.java) {
            service.confirm(r.orderId, user, OrderConfirmationRequest(r.compositionVersion!!, r.totalPaymentAmount)) }
    }

    @Test fun `JSON 가격 판매 방식 SKU와 확인 부가 입력은 HTTP 400이다`() {
        val controller = OrderController(legacy, service)
        val mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(GlobalExceptionHandler()).build()
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(user, null, emptyList())
        for (field in listOf("unitPrice", "salesType", "skuId")) {
            mvc.perform(post("/orders").header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"items":[{"productId":"${UUID.randomUUID()}","quantity":1,"$field":"forged"}]}"""))
                .andExpect(status().isBadRequest)
        }
        mvc.perform(post("/orders/${UUID.randomUUID()}/confirmation").contentType(MediaType.APPLICATION_JSON)
            .content("""{"compositionVersion":1,"totalPaymentAmount":1,"salesType":"MARKETPLACE"}"""))
            .andExpect(status().isBadRequest)
    }

    @Test fun `입점 단독은 기존 API에 그대로 위임한다`() {
        val market = marketplace(); val req = request(market to 1); val key = UUID.randomUUID().toString()
        val result = OrderResponse(null, 0, 0, 0, 0, 0, OrdersStatus.ORDER_FAILED, null, emptyList(), createdAt = Instant.now(), updatedAt = Instant.now())
        `when`(legacy.orders(req, user, key)).thenReturn(result)
        assertEquals(result, create(req, key)); verify(legacy).orders(req, user, key)
        assertEquals(0, attempts.count()); assertEquals(0, calls.get())
    }

    @Test fun `Redis 선행 해제와 품절 종결은 늦은 재시도와 원본 불일치를 막는다`() {
        val sku = marketplace(1); val orderId = UUID.randomUUID(); val rid = UUID.randomUUID(); val expiry = Instant.now().plusSeconds(600)
        assertTrue(inventory.releaseIntent(sku.toString(), orderId, rid, 1, expiry).successful)
        assertThrows(InventoryReservationTerminalStateException::class.java) {
            inventory.reserve(sku.toString(), orderId, 1, 600, rid, expiry, true) }
        assertEquals(1, inventory.getAvailableStock(sku.toString()))
        val rejected = UUID.randomUUID()
        assertNull(inventory.reserve(sku.toString(), orderId, 2, 600, rejected, expiry, true))
        inventory.initializeStock(sku.toString(), 10)
        assertThrows(InventoryReservationTerminalStateException::class.java) {
            inventory.reserve(sku.toString(), orderId, 2, 600, rejected, expiry, true) }
        assertFalse(inventory.releaseIntent(sku.toString(), UUID.randomUUID(), rid, 1, expiry).successful)
        assertEquals(10, inventory.getAvailableStock(sku.toString()))
    }

    @Test fun `요청 중복과 금액 오버플로는 원격 부작용 전에 롤백한다`() {
        val sku = direct(price = Long.MAX_VALUE)
        assertThrows(ArithmeticException::class.java) { create(request(sku to 2)) }
        assertThrows(IllegalArgumentException::class.java) { create(request(sku to 1, sku to 1)) }
        assertEquals(0, attempts.count()); assertEquals(0, orders.count()); assertEquals(0, calls.get())
    }

    @Test fun `동시 같은 키 요청은 같은 의도와 원격 예약 하나로 수렴한다`() {
        val sku = direct(); val req = request(sku to 2); val key = UUID.randomUUID().toString()
        val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        try {
            val results = (1..6).map { pool.submit<UUID?> {
                start.await()
                try { create(req, key).orderId } catch (_: OrderIdempotencyInProgressException) { null }
            } }
            start.countDown()
            val ids = results.mapNotNull { it.get(20, TimeUnit.SECONDS) }
            assertTrue(ids.isNotEmpty()); assertEquals(1, ids.distinct().size)
            assertEquals(1, attempts.count()); assertEquals(2, reserved(sku))
            assertEquals(1, rows().queryForObject("select count(*) from inventory_reservation", Int::class.java))
        } finally { pool.shutdownNow() }
    }

    @Test fun `원격 성공 뒤 로컬 Outbox 실패는 의도를 보존하고 같은 예약에서 재개한다`() {
        val market = marketplace(); val sku = direct(); var id: UUID? = null
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT ck_s4_outbox_failure CHECK (event_type <> 'INVENTORY_STOCK_ADJUSTED')")
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
                create(request(market to 1, sku to 1)) }
            id = attempts.findAll().single().id
            assertEquals(9, inventory.getAvailableStock(market.toString()))
            assertEquals(0, outbox.count())
            assertTrue(state.snapshot(id).items.first().state == AttemptItemState.PENDING)
        } finally { jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT ck_s4_outbox_failure") }
        val resumed = service.recover(requireNotNull(id), user)
        assertEquals(OrdersStatus.ORDER_AWAITING_CONFIRMATION, resumed.status)
        assertEquals(1, reserved(sku)); assertEquals(9, inventory.getAvailableStock(market.toString()))
        assertEquals(1, outbox.findAll().count { it.eventType == "INVENTORY_STOCK_ADJUSTED" })
    }

    @Test fun `위조된 원격 예약 원본과 미구성 내부 인증은 품절로 처리하지 않는다`() {
        val sku = direct(); mode = "wrong-original"
        val r = create(request(sku to 1))
        assertEquals(OrdersStatus.ORDER_PROCESSING, r.status)
        assertTrue(r.failedItems.isEmpty()); assertEquals(1, r.pendingItems.size)
        calls.set(0)
        val item = state.snapshot(r.orderId!!).items.single()
        assertNull(InventoryServiceClient(mapper, proxyUrl, "").resolve(r.orderId, item, r.expiresAt!!))
        assertEquals(0, calls.get())
        mode = "normal"
        assertEquals(OrdersStatus.ORDER_AWAITING_CONFIRMATION, service.recover(r.orderId, user).status)
    }

    @Test fun `직매입 무료 배송 기준은 서버 정책으로 계산한다`() {
        val sku = direct(price = 100000)
        val r = create(request(sku to 1))
        assertEquals(0, r.totalDeliveryFee); assertEquals(100000, r.totalPaymentAmount)
    }

    @Test fun `V7 SQL은 주문 참조와 구성 버전 비용 JSON 배열 제약을 적용한다`() {
        source.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { sql ->
                    sql.execute("CREATE SCHEMA s4_sql_contract")
                    sql.execute("SET LOCAL search_path TO s4_sql_contract")
                    sql.execute("CREATE TABLE orders (id UUID PRIMARY KEY)")
                    val id = UUID.randomUUID()
                    sql.execute("INSERT INTO orders VALUES ('$id')")
                    sql.execute(javaClass.classLoader.getResourceAsStream("db/migration/V7__mixed_order_attempt.sql")!!.bufferedReader().readText())
                    val prefix = "INSERT INTO mixed_order_attempt (id, items_json, expires_at, delivery_fee, free_delivery_threshold, commission_rate, composition_version, confirmed_version) VALUES "
                    for (values in listOf("('$id','[]',now(),0,0,0.1,1,null)",
                        "('$id','[{}]',now(),-1,0,0.1,1,null)", "('$id','[{}]',now(),0,0,0.1,1,2)",
                        "('${UUID.randomUUID()}','[{}]',now(),0,0,0.1,1,null)")) {
                        val point = connection.setSavepoint()
                        assertThrows(java.sql.SQLException::class.java) { sql.execute(prefix + values) }
                        connection.rollback(point)
                    }
                    sql.execute(prefix + "('$id','[{}]',now(),0,0,0.1,1,1)")
                }
            } finally { connection.rollback() }
        }
    }

    companion object {
        class TestClock : Clock() {
            @Volatile var time = Instant.now()
            override fun instant(): Instant = time
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
        }
        val testClock = TestClock()
        val token = UUID.randomUUID().toString().repeat(2)
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmStatic val stockDb = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmStatic val redisContainer = GenericContainer<Nothing>("redis:7.2-alpine").apply { withExposedPorts(6379) }
        lateinit var remoteContext: ConfigurableApplicationContext
        lateinit var proxy: HttpServer
        lateinit var proxyPool: ExecutorService
        lateinit var proxyUrl: String
        @Volatile var mode = "normal"
        @Volatile var finishBodies = CountDownLatch(1)
        @Volatile var onPut: ((String) -> Unit)? = null
        val calls = AtomicInteger()
        @DynamicPropertySource @JvmStatic fun properties(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", postgres::getJdbcUrl)
            r.add("spring.datasource.username", postgres::getUsername)
            r.add("spring.datasource.password", postgres::getPassword)
            r.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
        }
        @BeforeAll @JvmStatic fun startRemote() {
            val root = Path.of("..").toAbsolutePath().normalize()
            val locations = listOf("inventory-service", "common-outbox", "common-idempotency").joinToString(",") {
                "filesystem:${root.resolve("$it/src/main/resources/db/migration")}" }
            remoteContext = SpringApplicationBuilder(S4InventoryConfiguration::class.java).run(
                "--spring.config.location=optional:classpath:/s4-empty.yml", "--spring.profiles.active=s4-test",
                "--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=${stockDb.jdbcUrl}",
                "--spring.datasource.username=${stockDb.username}", "--spring.datasource.password=${stockDb.password}",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.locations=$locations",
                "--spring.kafka.listener.auto-startup=false", "--jwt.secret=${UUID.randomUUID().toString().repeat(2)}",
                "--inventory.service-token=$token", "--logging.level.root=WARN")
            val port = remoteContext.environment.getProperty("local.server.port")!!.toInt()
            proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            proxyPool = Executors.newCachedThreadPool(); proxy.executor = proxyPool
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
            proxy.createContext("/") { exchange ->
                calls.incrementAndGet()
                try {
                    val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    val current = mode
                    val bodyMayFinish = finishBodies
                    if (exchange.requestMethod == "PUT") onPut?.invoke(body)
                    if (current == "release-failure" && exchange.requestMethod == "POST") {
                        exchange.sendResponseHeaders(503, -1)
                    } else {
                        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port${exchange.requestURI}"))
                            .header("Authorization", exchange.requestHeaders.getFirst("Authorization"))
                            .header("Content-Type", "application/json")
                            .method(exchange.requestMethod, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                        val result = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                        if (current == "timeout" && exchange.requestMethod == "PUT") Thread.sleep(2300)
                        if (current == "lost-response" && result.statusCode() == 200) exchange.sendResponseHeaders(503, -1)
                        else {
                            exchange.responseHeaders.add("Content-Type", "application/json")
                            val data = if (current == "wrong-original" && result.statusCode() == 200) {
                                val mapper = remoteContext.getBean(ObjectMapper::class.java)
                                val tree = mapper.readTree(result.body())
                                (tree.required("reservation") as com.fasterxml.jackson.databind.node.ObjectNode)
                                    .put("quantity", 999999)
                                mapper.writeValueAsBytes(tree)
                            } else result.body()
                            exchange.sendResponseHeaders(result.statusCode(), data.size.toLong())
                            if (current == "body-timeout" && result.statusCode() == 200) {
                                exchange.responseBody.write(data, 0, 1)
                                exchange.responseBody.flush()
                                check(bodyMayFinish.await(15, TimeUnit.SECONDS))
                                exchange.responseBody.write(data, 1, data.size - 1)
                            } else exchange.responseBody.write(data)
                        }
                    }
                } catch (_: java.io.IOException) { }
                finally { exchange.close() }
            }
            proxy.start(); proxyUrl = "http://127.0.0.1:${proxy.address.port}"
        }
        @AfterAll @JvmStatic fun stopRemote() { proxy.stop(0); proxyPool.shutdownNow(); remoteContext.close() }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
@EntityScan(basePackages = ["com.eventfulcommerce.order.domain.entity", "com.eventfulcommerce.common"])
@EnableJpaRepositories(basePackages = ["com.eventfulcommerce.order.repository", "com.eventfulcommerce.common.repository"])
@Import(MixedOrdersService::class, OutboxEventService::class, OutboxRetryPolicy::class, OutboxPublisherProperties::class)
class S4OrderConfiguration {
    @Bean fun clock(): Clock = MixedOrdersIntegrationTest.testClock
    @Bean fun legacy(): OrdersService = mock(OrdersService::class.java)
    @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    @Bean fun client(mapper: ObjectMapper) = InventoryServiceClient(mapper, MixedOrdersIntegrationTest.proxyUrl, MixedOrdersIntegrationTest.token)
    @Bean fun connectionFactory() = LettuceConnectionFactory(RedisStandaloneConfiguration(
        MixedOrdersIntegrationTest.redisContainer.host, MixedOrdersIntegrationTest.redisContainer.getMappedPort(6379)))
    @Bean fun redis(factory: LettuceConnectionFactory) = StringRedisTemplate(factory)
    @Bean fun inventory(redis: StringRedisTemplate) = InventoryReservationService(redis, 3600)
    @Bean fun state(orders: OrdersRepository, attempts: MixedOrderAttemptRepository, claims: OrderRequestIdempotencyRepository,
        products: ProductReadModelRepository, mapper: ObjectMapper, outbox: OutboxEventService, clock: Clock): MixedOrderStateService =
        MixedOrderStateService(orders, attempts, claims, products, mapper, outbox, clock, 0.1, 86400, 3000, 100000)
}

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = [RedissonAutoConfigurationV2::class])
@EnableJpaAuditing
@EntityScan(basePackages = ["com.eventfulcommerce.inventory.domain", "com.eventfulcommerce.common"])
@EnableJpaRepositories(basePackages = ["com.eventfulcommerce.inventory.repository", "com.eventfulcommerce.common.repository"])
@Import(InventoryService::class, ReservationService::class, com.eventfulcommerce.inventory.service.FulfillmentService::class,
    com.eventfulcommerce.inventory.controller.InventoryController::class,
    com.eventfulcommerce.inventory.controller.ReservationController::class,
    com.eventfulcommerce.inventory.controller.InventoryExceptionHandler::class,
    com.eventfulcommerce.inventory.config.SecurityConfig::class,
    JwtConfig::class, JwtTokenProvider::class, OutboxEventService::class, OutboxRetryPolicy::class, OutboxPublisherProperties::class)
class S4InventoryConfiguration {
    @Bean fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = [RedissonAutoConfigurationV2::class])
@Import(S4OrderConfiguration::class)
class S4RecoveryConfiguration
