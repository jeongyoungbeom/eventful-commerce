package com.eventfulcommerce.s5

import com.eventfulcommerce.common.*
import com.eventfulcommerce.common.auth.*
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.inventory.domain.*
import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.repository.InventorySkuRepository
import com.eventfulcommerce.inventory.service.InventoryService
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.*
import com.eventfulcommerce.order.dto.OrderResponse
import com.eventfulcommerce.order.repository.*
import com.eventfulcommerce.order.request.*
import com.eventfulcommerce.order.service.*
import com.eventfulcommerce.payment.domain.PaymentWebhookRequest
import com.eventfulcommerce.payment.repository.*
import com.eventfulcommerce.payment.service.*
import com.eventfulcommerce.settlement.repository.SettlementRepository
import com.eventfulcommerce.settlement.service.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.redisson.api.RedissonClient
import org.redisson.spring.starter.RedissonAutoConfigurationV2
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.*
import org.springframework.data.jpa.repository.config.*
import org.springframework.data.redis.connection.*
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.*
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.*
import java.net.*
import java.net.http.*
import java.nio.file.Path
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import javax.sql.DataSource

@Testcontainers
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.show-sql=false"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [S5OrderConfiguration::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MixedPaymentsIntegrationTest {
    @Autowired lateinit var service: MixedOrdersService
    @Autowired lateinit var state: MixedOrderStateService
    @Autowired lateinit var orders: OrdersRepository
    @Autowired lateinit var attempts: MixedOrderAttemptRepository
    @Autowired lateinit var products: ProductReadModelRepository
    @Autowired lateinit var claims: OrderRequestIdempotencyRepository
    @Autowired lateinit var outbox: OutboxEventRepository
    @Autowired lateinit var redis: StringRedisTemplate
    @org.springframework.boot.test.mock.mockito.SpyBean lateinit var inventory: InventoryReservationService
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var source: DataSource
    @Autowired lateinit var manager: PlatformTransactionManager
    @Autowired lateinit var paymentEvents: com.eventfulcommerce.order.message.PaymentEventsConsumer
    @Autowired lateinit var shippingEvents: com.eventfulcommerce.order.message.ShippingEventsConsumer
    @Autowired lateinit var directEvents: com.eventfulcommerce.order.message.DirectInventoryEventsConsumer
    @org.springframework.boot.test.mock.mockito.SpyBean lateinit var legacy: OrdersService
    private val user = UUID.randomUUID()
    private val money get() = moneyContext.getBean(PaymentService::class.java)
    private val webhook get() = moneyContext.getBean(PaymentWebhookService::class.java)
    private val payments get() = moneyContext.getBean(PaymentRepository::class.java)
    private val refunds get() = moneyContext.getBean(PaymentRefundRepository::class.java)
    private val moneyOutbox get() = moneyContext.getBean(OutboxEventRepository::class.java)
    private val settlements get() = moneyContext.getBean(SettlementRepository::class.java)
    private val settlementConsumer get() = moneyContext.getBean(com.eventfulcommerce.settlement.message.PaymentEventsConsumer::class.java)
    private val stockJdbc get() = stockContext.getBean(JdbcTemplate::class.java)
    private val jdbc get() = JdbcTemplate(source)
    private val delivered = mutableSetOf<UUID>()

    @BeforeEach fun clean() {
        mode = "normal"; onPost = null; delivered.clear(); clock.time = Instant.now()
        if (!schemaApplied) {
            jdbc.execute(Path.of("../order-service/src/main/resources/db/migration/V8__mixed_payment_state.sql").toFile().readText())
            schemaApplied = true
        }
        attempts.deleteAll(); claims.deleteAll(); outbox.deleteAll(); orders.deleteAll(); products.deleteAll()
        redis.execute { it.serverCommands().flushAll(); null }
        stockJdbc.execute("TRUNCATE inventory_reservation, inventory_warehouse, inventory_supplier, inventory_sku, outbox_event, processed_event CASCADE")
        moneyContext.getBean(JdbcTemplate::class.java).execute("TRUNCATE payment, settlements, outbox_event, processed_event CASCADE")
    }
    @AfterEach fun after() { onPost = null; mode = "normal"; SecurityContextHolder.clearContext() }
    private fun market(): UUID {
        val id = UUID.randomUUID()
        products.saveAndFlush(ProductReadModel(id, UUID.randomUUID(), "입점", 12000, 10, "테스트"))
        inventory.initializeStock(id.toString(), 10)
        return id
    }
    private fun direct(price: Long = 10000): UUID {
        val id = UUID.randomUUID()
        products.saveAndFlush(ProductReadModel(id, SalesType.OFFICIAL_SELLER_ID, "직매입", price, 0, "테스트", salesType = SalesType.DIRECT_PURCHASE))
        stockContext.getBean(InventorySkuRepository::class.java).saveAndFlush(InventorySku(id, "직매입"))
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(user, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        try {
            val api = stockContext.getBean(InventoryService::class.java)
            val w = api.createWarehouse(WarehouseRequest(UUID.randomUUID().toString(), "창고", "서울"))
            val supplier = api.createSupplier(SupplierRequest(UUID.randomUUID().toString(), "공급사"))
            val po = api.createPurchaseOrder(UUID.randomUUID(), PurchaseOrderRequest(supplier.id, w.id, listOf(PurchaseLineRequest(id, 100, 1))))
            api.receive(UUID.randomUUID(), InboundRequest(po.order.id, "S5 실제 입고", listOf(InboundLineRequest(
                po.lines.single().id, UUID.randomUUID().toString(), LocalDate.now(clock).plusDays(1), LotQuality.GOOD, 100))))
        } finally { SecurityContextHolder.clearContext() }
        return id
    }
    private fun create(vararg ids: UUID) = service.orders(OrdersRequest(ids.map { OrderItemRequest(it, 1) }), user, UUID.randomUUID().toString())
    private fun confirm(r: OrderResponse): OrderResponse = service.confirm(r.orderId!!, user, OrderConfirmationRequest(r.compositionVersion!!, r.totalPaymentAmount))
    private fun message(e: OutboxEvent) = OutboxEventMessage(e.id, e.aggregateType, e.aggregateId, e.eventType, e.createdAt, e.payload)
    private fun register(r: OrderResponse) = money.handleOrderCreated(message(outbox.findAll().single { it.eventType == "ORDER_RESERVED" && it.aggregateId == r.orderId }))
    private fun currentPayment(id: UUID) = TransactionTemplate(moneyContext.getBean(PlatformTransactionManager::class.java)).execute { payments.findByOrderId(id) }!!
    private fun pay(r: OrderResponse, result: String = "SUCCESS") {
        register(r)
        webhook.handle(PaymentWebhookRequest(r.orderId!!, result, "S5-${r.orderId}", r.totalPaymentAmount))
        deliverPayment()
    }
    private fun deliverPayment() {
        moneyOutbox.findAll().sortedBy { it.createdAt }.filter { it.id !in delivered }.forEach { event ->
            settlementConsumer.consume(mapper.writeValueAsString(message(event)))
            paymentEvents.receive(mapper.writeValueAsString(message(event)))
            delivered.add(event.id)
        }
    }
    private fun deliverCancellation() {
        outbox.findAll().sortedBy { it.createdAt }.filter { it.eventType in setOf("ORDER_CANCELED", "ORDER_CANCELLATION_RECONCILIATION_REQUESTED") }.forEach {
            if (it.eventType == "ORDER_CANCELED") money.handleOrderCanceled(message(it)) else money.handleOrderCancellationReconciliation(message(it)) }
    }
    private fun quantity(sku: UUID, name: String) = stockJdbc.queryForObject("select coalesce(sum($name),0) from inventory_stock_balance where sku_id=?", Long::class.java, sku)!!
    private fun item(r: OrderResponse, sku: UUID) = r.sellerOrders.flatMap { it.items }.single { it.productId == sku }
    private fun parallel(vararg actions: () -> Unit) {
        val pool = Executors.newFixedThreadPool(actions.size); val start = CountDownLatch(1)
        try {
            val tasks = actions.map { action -> pool.submit { start.await(); action() } }
            start.countDown(); tasks.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    @Test fun `확인 원본 ID 금액으로 단일 결제하고 입점만 자동 배송과 정산에 보낸다`() {
        val market = market(); val first = direct(); val second = direct(15000)
        val r = create(market, first, second)
        assertFalse(r.paymentReady); assertEquals(0, outbox.findAll().count { it.eventType == "ORDER_RESERVED" })
        val ready = confirm(r)
        assertTrue(ready.paymentReady); assertEquals(40000, ready.totalPaymentAmount)
        parallel({ confirm(r) }, { confirm(r) })
        assertEquals(1, outbox.findAll().count { it.eventType == "ORDER_RESERVED" })
        assertEquals(r.sellerOrders.map { it.sellerOrderId }.toSet(), ready.sellerOrders.map { it.sellerOrderId }.toSet())
        assertEquals(r.sellerOrders.flatMap { it.items }.map { it.orderItemId }.toSet(), ready.sellerOrders.flatMap { it.items }.map { it.orderItemId }.toSet())
        pay(ready)
        val paid = service.getOrder(r.orderId!!, user)
        assertEquals(OrdersStatus.ORDER_CONFIRMED, paid.status); assertFalse(paid.paymentReady)
        assertEquals(1, quantity(first, "allocated")); assertEquals(0, quantity(first, "reserved")); assertEquals(100, quantity(first, "on_hand"))
        assertEquals(9, inventory.getAvailableStock(market.toString()))
        assertEquals(InventoryReservationState.COMMITTED, inventory.getReservationMetadata(market.toString(), item(r, market).reservationId)!!.state)
        assertEquals(1, settlements.count()); assertEquals(12000, settlements.findAll().single().totalAmount)
        assertEquals(1200, settlements.findAll().single().platformFee)
        val confirmed = outbox.findAll().filter { it.eventType == "ORDER_CONFIRMED" }.map { mapper.readValue(it.payload, OrderConfirmedPayload::class.java) }
        assertEquals(1, confirmed.size); assertEquals(SalesType.MARKETPLACE, confirmed.single().sellerOrders.single().salesType)
        deliverPayment(); service.recover(r.orderId)
        assertEquals(1, quantity(second, "allocated")); assertEquals(0, refunds.count())
    }

    @Test fun `S6 창고 취소 대기는 환불하지 않고 거절 품목과 별개 품목의 취소를 보존한다`() {
        val first = direct(); val second = direct(15000)
        val r = confirm(create(first, second)); pay(r)
        val line = item(r, first)
        // 실제 재고 HTTP의 응답 원본은 유지하고 창고 결정만 저장한다.
        val payload = mapper.readValue(outbox.findAll().single { it.eventType == "DIRECT_FULFILLMENT_REQUESTED" && it.payload.contains(line.orderItemId.toString()) }.payload, DirectFulfillmentPayload::class.java)
        stockContext.getBean(com.eventfulcommerce.inventory.service.FulfillmentService::class.java).create(payload)
        val api = stockContext.getBean(com.eventfulcommerce.inventory.service.FulfillmentService::class.java)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(user, null, listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        val action = OutboundActionRequest("S6 피킹", api.get(line.reservationId).lines.map { LotQuantity(it.lotId, it.quantity) })
        api.act(line.reservationId, "pick", action)
        assertEquals(OrderCancellationOutcome.PENDING, service.cancelItem(r.orderId!!, line.orderItemId, user))
        deliverCancellation(); assertEquals(0, refunds.count()); assertEquals(1, quantity(first, "allocated"))
        api.decide(line.reservationId, CancellationDecisionRequest(false, "창고 거절"))
        assertEquals(OrderCancellationOutcome.REJECTED, service.cancelItem(r.orderId, line.orderItemId, user))
        assertEquals(OrderCancellationOutcome.CANCELED, service.cancelItem(r.orderId, item(r, second).orderItemId, user))
        deliverCancellation(); deliverPayment()
        assertEquals(1, refunds.count()); assertEquals(item(r, second).orderItemId, refunds.findAll().single().orderItemId)
        assertEquals(OrderItemStatus.CONFIRMED, item(service.getOrder(r.orderId, user), first).status)
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `S6 거절 후 기한 경과와 실제 창고 회수 API는 최초 비용으로 한 품목만 환불한다`(observeRejection: Boolean) {
        val first = direct(); val second = direct(15000)
        val r = confirm(create(first, second)); pay(r)
        val line = item(r, first)
        val payload = mapper.readValue(outbox.findAll().single { it.eventType == "DIRECT_FULFILLMENT_REQUESTED" &&
            it.payload.contains(line.orderItemId.toString()) }.payload, DirectFulfillmentPayload::class.java)
        stockContext.getBean(com.eventfulcommerce.inventory.service.FulfillmentService::class.java).create(payload)
        val port = stockContext.environment.getProperty("local.server.port")!!.toInt()
        val admin = stockContext.getBean(JwtTokenProvider::class.java).createAccessToken(user, UserRole.ADMIN)
        val http = HttpClient.newHttpClient()
        fun warehouse(method: String, suffix: String, body: Any? = null): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/inventory/outbounds/${line.reservationId}$suffix"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer $admin").header("Content-Type", "application/json")
                .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString())
        val held = mapper.readTree(warehouse("GET", "").body()).required("lines")
        val action = OutboundActionRequest("실물 전량 확인", held.map { LotQuantity(UUID.fromString(it.required("lotId").asText()), it.required("quantity").asLong()) })
        assertEquals(200, warehouse("POST", "/pick", action).statusCode())
        val mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            com.eventfulcommerce.order.controller.OrderController(legacy, service)).setControllerAdvice(
            com.eventfulcommerce.order.exception.GlobalExceptionHandler()).build()
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(user, null, emptyList())
        val path = "/orders/${r.orderId}/items/${line.orderItemId}/cancel"
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted)
        val rejection = CancellationDecisionRequest(false, "최초 회수 불가")
        assertEquals(200, warehouse("PUT", "/cancellation-decision", rejection).statusCode())
        if (observeRejection) mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict)
        assertEquals(409, warehouse("PUT", "/ineligibility-cancellation", action).statusCode())
        clock.time = clock.instant().plusSeconds(86400)
        assertEquals(409, warehouse("POST", "/pack", action).statusCode())
        deliverCancellation(); assertEquals(0, refunds.count()); assertEquals(1, quantity(first, "allocated"))
        assertEquals(200, warehouse("PUT", "/ineligibility-cancellation", action).statusCode())
        assertEquals(0, quantity(first, "allocated")); assertEquals(100, quantity(first, "on_hand"))
        val event = stockContext.getBean(OutboxEventRepository::class.java).findAll().single { it.eventType == "OUTBOUND_INELIGIBILITY_CANCELED" }
        val encoded = mapper.writeValueAsString(message(event))
        val forged = message(event).copy(payload = mapper.writeValueAsString(payload.copy(quantity = 99)))
        assertThrows(IllegalArgumentException::class.java) { directEvents.receive(mapper.writeValueAsString(forged)) }
        if (!observeRejection) service.recover(r.orderId!!)
        else {
            jdbc.execute("alter table outbox_event add constraint s6_fail check (event_type <> 'ORDER_CANCELED')")
            try { assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) { directEvents.receive(encoded) } }
            finally { jdbc.execute("alter table outbox_event drop constraint s6_fail") }
            assertEquals(AttemptItemState.ALLOCATED, state.snapshot(r.orderId!!).items.single { it.orderItemId == line.orderItemId }.state)
        }
        directEvents.receive(encoded); directEvents.receive(encoded)
        deliverCancellation(); deliverPayment()
        val after = service.getOrder(r.orderId!!, user)
        assertEquals(OrderItemStatus.CANCELED, item(after, first).status)
        assertEquals(OrderItemStatus.CONFIRMED, item(after, second).status)
        assertEquals(r.totalPaymentAmount, after.totalPaymentAmount); assertEquals(r.totalDeliveryFee, after.totalDeliveryFee)
        assertEquals(1, quantity(second, "allocated")); assertEquals(100, quantity(second, "on_hand"))
        assertEquals(1, refunds.count()); assertEquals(line.orderItemId, refunds.findAll().single().orderItemId)
        assertEquals(line.totalAmount + line.deliveryFeeAllocation, refunds.findAll().single().amount)
        assertEquals(1, outbox.findAll().count { it.eventType == "ORDER_CANCELED" })
        assertEquals(200, warehouse("PUT", "/ineligibility-cancellation", action).statusCode())
        val original = warehouse("PUT", "/cancellation-decision", rejection)
        assertEquals(200, original.statusCode()); assertFalse(mapper.readTree(original.body()).required("outbound").required("cancellationApproved").asBoolean())
        assertEquals(1, stockContext.getBean(OutboxEventRepository::class.java).findAll().count { it.eventType == "OUTBOUND_INELIGIBILITY_CANCELED" })
        directEvents.receive(encoded); deliverCancellation(); deliverPayment(); assertEquals(1, refunds.count())
    }

    @Test fun `같은 플랫폼 판매자 두 품목은 독립 동시 환불하고 최초 배송비만 한 번 돌려준다`() {
        val market = market(); val first = direct(); val second = direct(15000)
        val r = confirm(create(market, first, second)); pay(r)
        parallel({ service.cancelItem(r.orderId!!, item(r, first).orderItemId, user) },
            { service.cancelItem(r.orderId!!, item(r, second).orderItemId, user) })
        parallel({ deliverCancellation() }, { deliverCancellation() })
        deliverPayment()
        assertEquals(2, refunds.count()); assertEquals(28000, refunds.findAll().sumOf { it.amount })
        assertEquals(1, refunds.findAll().map { it.sellerOrderId }.distinct().size)
        assertEquals(2, refunds.findAll().map { it.orderItemId }.distinct().size)
        assertEquals(0, quantity(first, "allocated")); assertEquals(100, quantity(first, "on_hand"))
        assertEquals(9, inventory.getAvailableStock(market.toString()))
        val after = service.getOrder(r.orderId!!, user)
        assertEquals(40000, after.totalPaymentAmount); assertEquals(3000, after.totalDeliveryFee)
        assertEquals(SellerOrderStatus.CONFIRMED, after.sellerOrders.single { it.salesType == SalesType.MARKETPLACE }.status)
        assertEquals(12000, settlements.findAll().single().totalAmount)
        assertNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
        service.cancelItem(r.orderId, item(r, first).orderItemId, user); deliverCancellation(); deliverPayment()
        assertEquals(2, refunds.count()); assertEquals(28000, currentPayment(r.orderId).refundedAmount)
    }

    @Test fun `결제 후 한 품목 할당 부적합은 그 품목만 보상하고 나머지 할당을 보존한다`() {
        val good = direct(); val bad = direct(15000); val market = market()
        val r = confirm(create(market, good, bad))
        val badItem = state.snapshot(r.orderId!!).items.single { it.productId == bad }
        stockJdbc.update("update inventory_warehouse set active=false where id=?", badItem.warehouseId)
        pay(r); deliverCancellation(); deliverPayment()
        assertEquals(1, quantity(good, "allocated")); assertEquals(0, quantity(bad, "reserved")); assertEquals(0, quantity(bad, "allocated"))
        assertEquals(9, inventory.getAvailableStock(market.toString()))
        assertEquals(item(r, bad).totalAmount + item(r, bad).deliveryFeeAllocation, refunds.findAll().single().amount)
        val after = service.getOrder(r.orderId, user)
        assertEquals(OrderItemStatus.CANCELED, item(after, bad).status); assertEquals(OrderItemStatus.CONFIRMED, item(after, good).status)
        assertEquals(OrdersStatus.ORDER_PARTIALLY_CANCELED, after.status)
        assertFalse(outbox.findAll().filter { it.eventType == "ORDER_CONFIRMED" }.any { it.payload.contains(badItem.sellerOrderId.toString()) })
    }

    @Test fun `할당 응답 유실은 미확인으로 보존되고 새 컨텍스트가 같은 ID로 복구한다`() {
        val sku = direct(); val r = confirm(create(sku)); mode = "lost-allocate"
        pay(r)
        assertEquals(1, quantity(sku, "allocated")); assertEquals(1, service.getOrder(r.orderId!!, user).pendingItems.size)
        assertNotNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
        mode = "normal"
        SpringApplicationBuilder(S5RecoveryConfiguration::class.java).run(
            "--spring.config.location=optional:classpath:/s5-empty.yml", "--spring.main.web-application-type=none",
            "--spring.datasource.url=${orderDb.jdbcUrl}", "--spring.datasource.username=${orderDb.username}", "--spring.datasource.password=${orderDb.password}",
            "--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.enabled=false", "--logging.level.root=WARN").use {
            it.getBean(MixedOrdersService::class.java).recover(r.orderId)
        }
        assertEquals(OrdersStatus.ORDER_CONFIRMED, service.getOrder(r.orderId, user).status)
        assertEquals(1, quantity(sku, "allocated")); assertEquals(1, stockJdbc.queryForObject("select count(*) from inventory_stock_ledger where document_type='ALLOCATION'", Int::class.java))
    }

    @Test fun `취소가 먼저 커밋되면 늦은 할당 요청은 품목을 되살리지 않는다`() {
        val sku = direct(); val r = confirm(create(sku)); val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        onPost = { path -> if (path.endsWith("/allocate")) { entered.countDown(); check(proceed.await(15, TimeUnit.SECONDS)) } }
        try {
            val paying = pool.submit { pay(r) }
            assertTrue(entered.await(8, TimeUnit.SECONDS))
            assertEquals(OrderCancellationOutcome.CANCELED, service.cancelItem(r.orderId!!, item(r, sku).orderItemId, user))
            proceed.countDown(); paying.get(15, TimeUnit.SECONDS)
            deliverCancellation(); deliverPayment()
            assertEquals(0, quantity(sku, "allocated")); assertEquals(0, quantity(sku, "reserved")); assertEquals(100, quantity(sku, "on_hand"))
            assertEquals("CANCELED", stockJdbc.queryForObject("select status from inventory_reservation", String::class.java))
            assertEquals(OrderItemStatus.CANCELED, item(service.getOrder(r.orderId, user), sku).status)
            assertEquals(r.totalPaymentAmount, refunds.findAll().single().amount)
        } finally { proceed.countDown(); pool.shutdownNow() }
    }

    @Test fun `할당 완료 후 취소 HTTP 실패는 환불을 보류하고 같은 ID로 복구한다`() {
        val sku = direct(); val other = direct(); val r = confirm(create(sku, other)); pay(r)
        mode = "cancel-failure"
        val cancelCalls = java.util.concurrent.atomic.AtomicInteger()
        onPost = { if (it.endsWith("/cancel")) cancelCalls.incrementAndGet() }
        assertEquals(OrderCancellationOutcome.PENDING, service.cancelItem(r.orderId!!, item(r, sku).orderItemId, user))
        assertEquals(3, cancelCalls.get())
        assertEquals(1, quantity(sku, "allocated")); assertTrue(outbox.findAll().none { it.eventType == "ORDER_CANCELED" })
        assertNotNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
        mode = "normal"
        onPost = { path -> if (path.endsWith("/cancel")) TransactionTemplate(manager).execute {
            jdbc.execute("SET LOCAL lock_timeout='500ms'"); assertNotNull(orders.findByIdForUpdate(r.orderId)) } }
        service.recover(r.orderId); deliverCancellation(); deliverPayment()
        assertEquals(0, quantity(sku, "allocated")); assertEquals(1, quantity(other, "allocated")); assertEquals(1, refunds.count())
    }

    @Test fun `확인 후 예약 만료와 늦은 결제 성공은 전체 보상하고 원본 금액을 보존한다`() {
        val sku = direct(); val market = market(); val r = confirm(create(sku, market)); register(r)
        clock.time = clock.time.plusSeconds(601)
        val expired = service.getOrder(r.orderId!!, user)
        assertFalse(expired.paymentReady); assertEquals(OrdersStatus.ORDER_EXPIRED, expired.status)
        assertEquals(r.totalPaymentAmount, expired.totalPaymentAmount)
        deliverCancellation()
        assertEquals(0, refunds.count()); assertTrue(currentPayment(r.orderId).cancellationRequested)
        webhook.handle(PaymentWebhookRequest(r.orderId, "SUCCESS", "S5-${r.orderId}", r.totalPaymentAmount)); deliverPayment()
        assertEquals(r.totalPaymentAmount, currentPayment(r.orderId).refundedAmount)
        assertEquals(0, quantity(sku, "allocated")); assertEquals(10, inventory.getAvailableStock(market.toString()))
        assertEquals(OrdersStatus.ORDER_CANCELED, service.getOrder(r.orderId, user).status)
        assertTrue(settlements.findAll().all { it.totalAmount == 0L })
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = [600, 601])
    fun `만료 조회 없이 기한 경계 이후 결제가 먼저 도착해도 전체 원본을 한 번만 환불한다`(elapsed: Long) {
        val sku = direct(); val market = market(); val r = confirm(create(sku, market))
        clock.time = r.expiresAt!!.minusSeconds(600).plusSeconds(elapsed)
        pay(r)
        assertTrue(outbox.findAll().none { it.eventType == "ORDER_CONFIRMED" })
        deliverCancellation(); deliverPayment()
        val after = service.getOrder(r.orderId!!, user)
        assertEquals(OrdersStatus.ORDER_CANCELED, after.status)
        assertEquals(r.totalPaymentAmount, after.totalPaymentAmount)
        assertEquals(r.totalDeliveryFee, after.totalDeliveryFee)
        assertTrue(after.sellerOrders.flatMap { it.items }.all { it.status == OrderItemStatus.CANCELED })
        assertEquals(0, quantity(sku, "reserved")); assertEquals(0, quantity(sku, "allocated")); assertEquals(100, quantity(sku, "on_hand"))
        assertEquals(10, inventory.getAvailableStock(market.toString()))
        assertEquals(InventoryReservationState.RELEASED, inventory.getReservationMetadata(market.toString(), item(r, market).reservationId)!!.state)
        assertEquals(2, refunds.count()); assertEquals(r.totalPaymentAmount, currentPayment(r.orderId).refundedAmount)
        assertEquals(r.totalPaymentAmount, refunds.findAll().sumOf { it.amount })
        assertTrue(settlements.findAll().all { it.totalAmount == 0L && it.platformFee == 0L && it.sellerAmount == 0L })
        val completed = moneyOutbox.findAll().single { it.eventType == "PAYMENT_COMPLETED" }
        paymentEvents.receive(mapper.writeValueAsString(message(completed).copy(eventId = UUID.randomUUID())))
        service.recover(r.orderId); deliverCancellation(); deliverPayment()
        assertEquals(2, refunds.count()); assertNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
        assertTrue(outbox.findAll().none { it.eventType == "ORDER_CONFIRMED" })
    }

    @Test fun `유효한 결제 뒤 실제 Redis 확정 전에 만료되면 입점만 보상하고 할당된 직매입을 보존한다`() {
        val sku = direct(); val market = market()
        clock.time = Instant.now().minusSeconds(595)
        val r = confirm(create(sku, market))
        val line = item(r, market)
        org.mockito.Mockito.doAnswer { call ->
            val remaining = Duration.between(Instant.now(), r.expiresAt!!.plusMillis(10)).toMillis()
            if (remaining > 0) Thread.sleep(remaining)
            call.callRealMethod()
        }.`when`(inventory).commit(market.toString(), line.reservationId, line.quantity, true)
        pay(r); deliverCancellation(); deliverPayment()
        val after = service.getOrder(r.orderId!!, user)
        assertEquals(OrdersStatus.ORDER_PARTIALLY_CANCELED, after.status)
        assertEquals(OrderItemStatus.CANCELED, item(after, market).status)
        assertEquals(OrderItemStatus.CONFIRMED, item(after, sku).status)
        assertEquals(10, inventory.getAvailableStock(market.toString()))
        assertEquals(1, quantity(sku, "allocated")); assertEquals(0, quantity(sku, "reserved")); assertEquals(100, quantity(sku, "on_hand"))
        assertEquals(12000, refunds.findAll().single().amount)
        assertEquals(SalesType.MARKETPLACE, refunds.findAll().single().salesType)
        assertTrue(outbox.findAll().none { it.eventType == "ORDER_CONFIRMED" })
        assertNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
    }

    @Test fun `결제 실패는 예약을 해제하고 할당과 실제 환불을 만들지 않는다`() {
        val sku = direct(); val r = confirm(create(sku)); pay(r, "FAILED"); deliverCancellation()
        assertEquals(0, quantity(sku, "reserved")); assertEquals(0, quantity(sku, "allocated")); assertEquals(0, refunds.count())
        assertEquals(OrdersStatus.ORDER_CANCELED, service.getOrder(r.orderId!!, user).status)
        assertNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
    }

    @Test fun `확인 Outbox 실패는 결제 원본 행과 확인 상태를 함께 롤백한다`() {
        val sku = direct(); val r = create(sku)
        jdbc.execute("alter table outbox_event add constraint s5_fail check (event_type <> 'ORDER_RESERVED')")
        try { assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) { confirm(r) } }
        finally { jdbc.execute("alter table outbox_event drop constraint s5_fail") }
        assertEquals(0, jdbc.queryForObject("select count(*) from seller_orders", Int::class.java))
        assertNull(state.snapshot(r.orderId!!).response.confirmedVersion)
        assertTrue(confirm(r).paymentReady)
    }

    @Test fun `취소 Outbox 실패 뒤 재고 종결은 남고 로컬 품목과 환불은 같은 ID로 복구한다`() {
        val sku = direct(); val r = confirm(create(sku)); pay(r)
        jdbc.execute("alter table outbox_event add constraint s5_fail check (event_type <> 'ORDER_CANCELED')")
        try { assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) { service.cancelItem(r.orderId!!, item(r, sku).orderItemId, user) } }
        finally { jdbc.execute("alter table outbox_event drop constraint s5_fail") }
        assertEquals(0, quantity(sku, "allocated")); assertEquals(AttemptItemState.CANCEL_PENDING, state.snapshot(r.orderId!!).items.single().state)
        service.recover(r.orderId); deliverCancellation(); deliverPayment()
        assertEquals(1, refunds.count()); assertEquals(r.totalPaymentAmount, refunds.findAll().single().amount)
        assertEquals(1, stockJdbc.queryForObject("select count(*) from inventory_stock_ledger where document_type='ALLOCATION_CANCEL'", Int::class.java))
    }

    @Test fun `품목 환불 원본 위조와 총액 초과는 거부하고 같은 원본 재발행은 최초 사실을 유지한다`() {
        val sku = direct(); val r = confirm(create(sku)); pay(r)
        service.cancelItem(r.orderId!!, item(r, sku).orderItemId, user)
        val event = outbox.findAll().single { it.eventType == "ORDER_CANCELED" }
        val original = mapper.readValue(event.payload, OrderCanceledPayload::class.java)
        for (target in listOf(original.canceledSellerOrders.single().copy(refundAmount = r.totalPaymentAmount + 1),
            original.canceledSellerOrders.single().let { it.copy(items = it.items.map { line -> line.copy(quantity = 2) }) })) {
            assertThrows(IllegalArgumentException::class.java) { money.handleOrderCanceled(message(event).copy(eventId = UUID.randomUUID(), payload = mapper.writeValueAsString(original.copy(canceledSellerOrders = listOf(target))))) }
        }
        deliverCancellation()
        val first = refunds.findAll().single(); assertEquals(r.totalPaymentAmount, first.amount)
        money.handleOrderCanceled(message(event).copy(eventId = UUID.randomUUID()))
        service.recover(r.orderId); deliverCancellation()
        assertEquals(1, refunds.count()); assertTrue(moneyOutbox.findAll().filter { it.eventType == "PAYMENT_REFUNDED" }
            .all { mapper.readValue(it.payload, PaymentRefundedPayload::class.java).refundId == first.id })
        deliverPayment(); assertNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
    }

    @Test fun `확인 금액과 결제 구성 위조 및 직매입 품목 API의 타인 접근을 거부한다`() {
        val sku = direct(); val r = create(sku)
        assertThrows(com.eventfulcommerce.order.exception.OrderCompositionConflictException::class.java) {
            service.confirm(r.orderId!!, user, OrderConfirmationRequest(r.compositionVersion!!, 1)) }
        confirm(r)
        val event = outbox.findAll().single { it.eventType == "ORDER_RESERVED" }
        val payload = mapper.readValue(event.payload, OrderReservedPayload::class.java)
        assertThrows(IllegalArgumentException::class.java) { money.handleOrderCreated(message(event).copy(payload = mapper.writeValueAsString(payload.copy(confirmedVersion = null)))) }
        assertEquals(0, payments.count())
        register(r)
        val payment = currentPayment(r.orderId!!)
        val completed = PaymentCompletedPayload(payment.id, r.orderId, user, r.totalPaymentAmount + 1, emptyList(), Instant.now())
        assertThrows(IllegalArgumentException::class.java) { service.paymentCompleted(completed) }
        assertThrows(com.eventfulcommerce.order.exception.OrderForbiddenException::class.java) { service.cancelItem(r.orderId, item(r, sku).orderItemId, UUID.randomUUID()) }
        assertEquals(1, quantity(sku, "reserved")); assertEquals(0, quantity(sku, "allocated"))
    }


    @Test fun `재고 취소 원장 실패는 할당과 종결을 함께 롤백하고 다음 복구에서 한 번만 해제한다`() {
        val sku = direct(); val r = confirm(create(sku)); pay(r)
        stockJdbc.execute("alter table inventory_stock_ledger add constraint s5_stock_fail check(document_type <> 'ALLOCATION_CANCEL')")
        try {
            assertEquals(OrderCancellationOutcome.PENDING, service.cancelItem(r.orderId!!, item(r, sku).orderItemId, user))
            assertEquals(1, quantity(sku, "allocated"))
            assertEquals("ALLOCATED", stockJdbc.queryForObject("select status from inventory_reservation", String::class.java))
            assertTrue(outbox.findAll().none { it.eventType == "ORDER_CANCELED" })
        } finally { stockJdbc.execute("alter table inventory_stock_ledger drop constraint s5_stock_fail") }
        service.recover(r.orderId!!); deliverCancellation(); deliverPayment()
        assertEquals(0, quantity(sku, "allocated")); assertEquals(1, refunds.count())
    }

    @Test fun `S5 SQL은 결제 상태와 품목 환불 키 금액 제약을 실제 DB에서 강제한다`() {
        val sku = direct(); val r = confirm(create(sku)); pay(r)
        assertTrue(jdbc.queryForObject("select mixed_order from orders where id=?", Boolean::class.java, r.orderId)!!)
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            jdbc.update("update mixed_order_attempt set payment_state='INVALID' where id=?", r.orderId) }
        service.cancelItem(r.orderId!!, item(r, sku).orderItemId, user); deliverCancellation()
        val db = moneyContext.getBean(JdbcTemplate::class.java)
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            db.update("update payment_refund set refund_key=?", UUID.randomUUID()) }
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            db.update("update payment set refunded_amount=amount+1") }
    }

    @Test fun `혼합 전체 취소도 입점 배송 승인 전 환불하지 않고 승인 뒤 기존 receipt와 수수료를 보존한다`() {
        val market = market(); val sku = direct(); val r = confirm(create(market, sku)); pay(r)
        assertEquals(OrderCancellationOutcome.PENDING, service.cancel(r.orderId!!, user))
        deliverCancellation(); deliverPayment()
        assertEquals(1, refunds.count()); assertEquals(SalesType.DIRECT_PURCHASE, refunds.findAll().single().salesType)
        assertEquals(12000, settlements.findAll().single().totalAmount)
        assertEquals(9, inventory.getAvailableStock(market.toString()))
        val request = mapper.readValue(outbox.findAll().single { it.eventType == "SHIPPING_CANCELLATION_REQUESTED" }.payload, ShippingCancellationRequestedPayload::class.java)
        val decision = ShippingCancellationDecidedPayload(request.requestId, r.orderId, request.sellerOrderId, user, true)
        val message = OutboxEventMessage(UUID.randomUUID(), "SHIPPING", r.orderId, "SHIPPING_CANCELLATION_DECIDED", Instant.now(), mapper.writeValueAsString(decision))
        shippingEvents.receive(mapper.writeValueAsString(message)); deliverCancellation(); deliverPayment()
        assertEquals(10, inventory.getAvailableStock(market.toString())); assertEquals(2, refunds.count())
        assertEquals(r.totalPaymentAmount, currentPayment(r.orderId).refundedAmount)
        val settlement = settlements.findAll().single()
        assertEquals(0, settlement.totalAmount); assertEquals(0, settlement.platformFee); assertEquals(0, settlement.sellerAmount)
        val db = moneyContext.getBean(JdbcTemplate::class.java)
        assertEquals(1, db.queryForObject("select count(*) from settlement_refund", Int::class.java))
        shippingEvents.receive(mapper.writeValueAsString(message.copy(eventId = UUID.randomUUID())))
        deliverCancellation(); deliverPayment(); assertEquals(2, refunds.count())
        assertNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
    }

    @Test fun `직매입 품목 취소 HTTP는 본인만 허용하고 다른 품목을 보존한다`() {
        val sku = direct(); val other = direct(); val r = confirm(create(sku, other)); pay(r)
        val mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            com.eventfulcommerce.order.controller.OrderController(legacy, service)).setControllerAdvice(
            com.eventfulcommerce.order.exception.GlobalExceptionHandler()).build()
        fun authenticate(id: UUID) { SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(id, null, emptyList()) }
        val path = "/orders/${r.orderId}/items/${item(r, sku).orderItemId}/cancel"
        authenticate(UUID.randomUUID())
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden)
        authenticate(user)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)
        assertEquals(0, quantity(sku, "allocated")); assertEquals(1, quantity(other, "allocated"))
    }

    @Test fun `확인과 전체 취소 경합은 주문 잠금의 최신 상태로 결정하고 예약을 남기지 않는다`() {
        val sku = direct(); val r = create(sku)
        parallel({ try { confirm(r) } catch (_: com.eventfulcommerce.order.exception.OrderCompositionConflictException) { } },
            { service.cancel(r.orderId!!, user) })
        val after = service.getOrder(r.orderId!!, user)
        assertEquals(OrdersStatus.ORDER_CANCELED, after.status)
        assertEquals(0, quantity(sku, "reserved")); assertEquals(0, quantity(sku, "allocated")); assertFalse(after.paymentReady)
        assertTrue(outbox.findAll().count { it.eventType == "ORDER_RESERVED" } <= 1)
    }

    @Test fun `0원 품목을 취소해도 다른 유료 품목의 결제를 유지하고 환불 대기를 남기지 않는다`() {
        val free = direct(0); val paid = direct(100000); val r = confirm(create(free, paid)); pay(r)
        service.cancelItem(r.orderId!!, item(r, free).orderItemId, user)
        assertEquals(0, quantity(free, "allocated")); assertEquals(1, quantity(paid, "allocated"))
        assertTrue(outbox.findAll().none { it.eventType == "ORDER_CANCELED" }); assertNull(attempts.findById(r.orderId).orElseThrow().nextRecoveryAt)
        assertEquals(100000, service.getOrder(r.orderId, user).totalPaymentAmount)
    }

    @Test fun `혼합 취소에서 입점 잠금을 얻지 못하면 완료로 응답하지 않고 재시도를 알린다`() {
        val market = market(); val sku = direct(); val r = confirm(create(market, sku)); pay(r)
        val sellerId = r.sellerOrders.single { it.salesType == SalesType.MARKETPLACE }.sellerOrderId
        org.mockito.Mockito.doReturn(OrderCancellationOutcome.LOCK_UNAVAILABLE).`when`(legacy)
            .cancelSellerOrder(r.orderId!!, sellerId, user)
        assertEquals(OrderCancellationOutcome.LOCK_UNAVAILABLE, service.cancel(r.orderId, user))
        assertEquals(0, quantity(sku, "allocated")); assertEquals(9, inventory.getAvailableStock(market.toString()))
        assertEquals(SellerOrderStatus.CONFIRMED, service.getOrder(r.orderId, user).sellerOrders.single { it.sellerOrderId == sellerId }.status)
    }

    companion object {
        class TestClock : Clock() {
            @Volatile var time = Instant.now()
            override fun instant() = time
            override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
        }
        private var schemaApplied = false
        val clock = TestClock()
        val token = UUID.randomUUID().toString().repeat(2)
        @Container @JvmStatic val orderDb = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmStatic val stockDb = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmStatic val moneyDb = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmStatic val redisContainer = GenericContainer<Nothing>("redis:7.2-alpine").apply { withExposedPorts(6379) }
        lateinit var stockContext: ConfigurableApplicationContext
        lateinit var moneyContext: ConfigurableApplicationContext
        lateinit var proxy: HttpServer
        lateinit var pool: ExecutorService
        lateinit var url: String
        @Volatile var mode = "normal"
        @Volatile var onPost: ((String) -> Unit)? = null
        @DynamicPropertySource @JvmStatic fun properties(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", orderDb::getJdbcUrl); r.add("spring.datasource.username", orderDb::getUsername)
            r.add("spring.datasource.password", orderDb::getPassword); r.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
        }
        @BeforeAll @JvmStatic fun start() {
            val root = Path.of("..").toAbsolutePath().normalize()
            val locations = listOf("inventory-service", "common-outbox", "common-idempotency").joinToString(",") { "filesystem:${root.resolve("$it/src/main/resources/db/migration")}" }
            stockContext = SpringApplicationBuilder(com.eventfulcommerce.s4.S4InventoryConfiguration::class.java, S5StockConfiguration::class.java).run(
                "--spring.config.location=optional:classpath:/s5-empty.yml", "--spring.profiles.active=s5-test", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=${stockDb.jdbcUrl}", "--spring.datasource.username=${stockDb.username}", "--spring.datasource.password=${stockDb.password}",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.flyway.locations=$locations", "--spring.kafka.listener.auto-startup=false",
                "--jwt.secret=${UUID.randomUUID().toString().repeat(2)}", "--inventory.service-token=$token", "--logging.level.root=WARN")
            moneyContext = SpringApplicationBuilder(S5MoneyConfiguration::class.java).run(
                "--spring.config.location=optional:classpath:/s5-empty.yml", "--spring.main.web-application-type=none",
                "--spring.datasource.url=${moneyDb.jdbcUrl}", "--spring.datasource.username=${moneyDb.username}", "--spring.datasource.password=${moneyDb.password}",
                "--spring.jpa.hibernate.ddl-auto=create-drop", "--spring.flyway.enabled=false", "--spring.kafka.listener.auto-startup=false", "--logging.level.root=WARN")
            val moneyJdbc = moneyContext.getBean(JdbcTemplate::class.java)
            moneyJdbc.execute("alter table payment_refund drop constraint uk_payment_refund_target")
            moneyJdbc.execute("alter table payment_refund add constraint old_seller_refund unique(payment_id, seller_order_id)")
            for (file in listOf("V3__payment_concurrency_and_refund_bounds.sql", "V5__item_refund_identity.sql"))
                moneyJdbc.execute(root.resolve("payment-service/src/main/resources/db/migration/$file").toFile().readText())
            val port = stockContext.environment.getProperty("local.server.port")!!.toInt()
            proxy = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0); pool = Executors.newCachedThreadPool(); proxy.executor = pool
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
            proxy.createContext("/") { exchange ->
                try {
                    val body = exchange.requestBody.readBytes(); val path = exchange.requestURI.toString(); val current = mode
                    if (exchange.requestMethod == "POST") onPost?.invoke(path)
                    if (current == "cancel-failure" && path.endsWith("/cancel")) exchange.sendResponseHeaders(503, -1)
                    else {
                        val result = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                            .header("Authorization", exchange.requestHeaders.getFirst("Authorization")).header("Content-Type", "application/json")
                            .method(exchange.requestMethod, HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray())
                        if (current == "lost-allocate" && path.endsWith("/allocate")) exchange.sendResponseHeaders(503, -1)
                        else { exchange.responseHeaders.add("Content-Type", "application/json"); exchange.sendResponseHeaders(result.statusCode(), result.body().size.toLong()); exchange.responseBody.write(result.body()) }
                    }
                } finally { exchange.close() }
            }
            proxy.start(); url = "http://127.0.0.1:${proxy.address.port}"
        }
        @AfterAll @JvmStatic fun stop() { proxy.stop(0); pool.shutdownNow(); moneyContext.close(); stockContext.close() }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
@EntityScan(basePackages = ["com.eventfulcommerce.order.domain.entity", "com.eventfulcommerce.common"])
@EnableJpaRepositories(basePackages = ["com.eventfulcommerce.order.repository", "com.eventfulcommerce.common.repository"])
@Import(MixedOrdersService::class, OutboxEventService::class, OutboxRetryPolicy::class, OutboxPublisherProperties::class,
    OrdersService::class, OrderCancelExecutor::class, OrderCancelService::class, OrderSagaService::class, IdempotencyHandler::class,
    com.eventfulcommerce.order.message.PaymentEventsConsumer::class, com.eventfulcommerce.order.message.ShippingEventsConsumer::class,
    com.eventfulcommerce.order.message.DirectInventoryEventsConsumer::class)
class S5OrderConfiguration {
    @Bean fun clock(): Clock = MixedPaymentsIntegrationTest.clock
    @Bean fun metrics() = mock(com.eventfulcommerce.common.metrics.EventfulBusinessMetrics::class.java)
    @Bean(destroyMethod = "shutdown") fun redisson(): RedissonClient = org.redisson.Redisson.create(org.redisson.config.Config().apply {
        useSingleServer().address = "redis://${MixedPaymentsIntegrationTest.redisContainer.host}:${MixedPaymentsIntegrationTest.redisContainer.getMappedPort(6379)}"
    })
    @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    @Bean fun client(mapper: ObjectMapper) = InventoryServiceClient(mapper, MixedPaymentsIntegrationTest.url, MixedPaymentsIntegrationTest.token)
    @Bean fun connectionFactory() = LettuceConnectionFactory(RedisStandaloneConfiguration(MixedPaymentsIntegrationTest.redisContainer.host, MixedPaymentsIntegrationTest.redisContainer.getMappedPort(6379)))
    @Bean fun redis(factory: LettuceConnectionFactory) = StringRedisTemplate(factory)
    @Bean fun inventory(redis: StringRedisTemplate) = InventoryReservationService(redis, 3600)
    @Bean fun state(orders: OrdersRepository, attempts: MixedOrderAttemptRepository, claims: OrderRequestIdempotencyRepository,
        products: ProductReadModelRepository, mapper: ObjectMapper, outbox: OutboxEventService, clock: Clock) =
        MixedOrderStateService(orders, attempts, claims, products, mapper, outbox, clock, 0.1, 86400, 3000, 100000)
}

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = [RedissonAutoConfigurationV2::class])
@EntityScan(basePackages = ["com.eventfulcommerce.payment.domain.entity", "com.eventfulcommerce.settlement.domain.entity", "com.eventfulcommerce.common"])
@EnableJpaRepositories(basePackages = ["com.eventfulcommerce.payment.repository", "com.eventfulcommerce.settlement.repository", "com.eventfulcommerce.common.repository"])
@Import(PaymentService::class, PaymentWebhookService::class, IdempotencyHandler::class, SettlementService::class,
    SettlementPayExecutor::class, com.eventfulcommerce.settlement.message.PaymentEventsConsumer::class)
class S5MoneyConfiguration {
    @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    @Bean fun metrics() = mock(com.eventfulcommerce.common.metrics.EventfulBusinessMetrics::class.java)
    @Bean fun redisson() = mock(RedissonClient::class.java)
}

@Configuration(proxyBeanMethods = false)
@Import(com.eventfulcommerce.inventory.controller.FulfillmentController::class)
class S5StockConfiguration {
    @Bean @Primary fun stockClock(): Clock = MixedPaymentsIntegrationTest.clock
}

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = [RedissonAutoConfigurationV2::class])
@Import(S5OrderConfiguration::class)
class S5RecoveryConfiguration
