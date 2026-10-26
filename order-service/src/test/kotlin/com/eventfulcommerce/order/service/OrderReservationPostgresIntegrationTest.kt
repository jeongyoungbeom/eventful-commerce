package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.*
import com.eventfulcommerce.common.metrics.EventfulBusinessMetrics
import com.eventfulcommerce.common.repository.OutboxEventRepository
import com.eventfulcommerce.common.repository.ProcessedEventRepository
import com.eventfulcommerce.order.domain.OrdersStatus
import com.eventfulcommerce.order.domain.entity.*
import com.eventfulcommerce.order.repository.*
import com.eventfulcommerce.shipping.domain.Shipping
import com.eventfulcommerce.shipping.repository.ShippingRepository
import com.eventfulcommerce.shipping.service.ShippingCompletionWorker
import com.eventfulcommerce.shipping.service.ShippingService
import com.eventfulcommerce.shipping.domain.ShippingStatus
import com.eventfulcommerce.shipping.service.ShippingCompletionService
import com.eventfulcommerce.shipping.service.ShippingFailureService
import com.eventfulcommerce.shipping.service.ShippingDispatchScheduler
import com.eventfulcommerce.order.scheduler.ShippingCancellationRecoveryScheduler
import com.eventfulcommerce.order.message.ShippingEventsConsumer
import com.eventfulcommerce.shipping.message.PaymentEventsConsumer
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.*
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@Testcontainers
@DataJpaTest(properties = ["spring.flyway.enabled=false", "spring.jpa.show-sql=false"])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [OrderReservationTestConfiguration::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderReservationPostgresIntegrationTest {
    @Autowired private lateinit var service: OrdersService
    @Autowired private lateinit var expiration: OrderExpirationService
    @Autowired private lateinit var cancellation: OrderCancelService
    @Autowired private lateinit var shipping: ShippingService
    @Autowired private lateinit var executor: OrderCancelExecutor
    @Autowired private lateinit var sellerOrders: SellerOrderRepository
    @Autowired private lateinit var completion: ShippingCompletionService
    @Autowired private lateinit var failure: ShippingFailureService
    @Autowired private lateinit var orders: OrdersRepository
    @Autowired private lateinit var sagas: OrderSagaRepository
    @Autowired private lateinit var receipts: OrderSagaRefundReceiptRepository
    @Autowired private lateinit var shipments: ShippingRepository
    @Autowired private lateinit var outbox: OutboxEventRepository
    @Autowired private lateinit var markers: ProcessedEventRepository
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var redisson: RedissonClient
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @SpyBean private lateinit var mapper: ObjectMapper
    @SpyBean private lateinit var inventory: InventoryReservationService

    @BeforeEach
    fun cleanUp() {
        outbox.deleteAll()
        shipments.deleteAll()
        receipts.deleteAll()
        sagas.deleteAll()
        orders.deleteAll()
        markers.deleteAll()
        redis.execute { connection -> connection.serverCommands().flushAll(); null }
    }

    @Test
    fun `결제 전 일부 취소 후 남은 판매자만 확정하고 실제 배송 소비자도 그 대상만 생성한다`() {
        val order = reservedOrder()
        val (canceled, kept) = order.sellerOrders
        assertTrue(cancellation.cancelSellerOrder(order.id, canceled.id, "결제 전 일부 취소"))
        val payment = payment(order)
        service.handlePaymentCompleted(payment)
        service.handlePaymentCompleted(payment)
        service.handlePaymentCompleted(payment.copy(eventId = UUID.randomUUID()))

        val actual = current(order)
        assertEquals(OrdersStatus.ORDER_PARTIALLY_CANCELED, actual.status)
        assertNull(actual.expiresAt)
        assertEquals(mapOf(canceled.id to SellerOrderStatus.CANCELED, kept.id to SellerOrderStatus.CONFIRMED),
            actual.sellerOrders.associate { it.id to it.status })
        assertInventory(canceled, InventoryReservationState.RELEASED, 10)
        assertInventory(kept, InventoryReservationState.COMMITTED, 8)
        val event = events("ORDER_CONFIRMED").single()
        val payload = mapper.readValue(event.payload, OrderConfirmedPayload::class.java)
        assertEquals(listOf(kept.id), payload.sellerOrders.map { it.sellerOrderId })
        assertEquals(kept.paymentAmount, payload.totalAmount)
        assertEquals(order.totalPaymentAmount, actual.totalPaymentAmount)

        shipping.handleOrderConfirmed(event.id, event.payload)
        shipping.handleOrderConfirmed(event.id, event.payload)
        shipping.handleOrderConfirmed(UUID.randomUUID(), event.payload)
        assertEquals(listOf(kept.id), shipments.findAll().map { it.sellerOrderId })
        assertEquals(0, events("SHIPPING_STARTED").size)
        assertEquals(ShippingStatus.PREPARING, shipments.findAll().single().status)
        assertFalse(expiration.expireOrder(order.id))
        assertEquals(1, events("ORDER_CANCELED").size)
    }

    @Test
    fun `결제 후 일부 취소는 확정된 잔여 주문을 만료시키거나 다시 확정하지 않는다`() {
        val order = reservedOrder(expired = true)
        val (canceled, kept) = order.sellerOrders
        service.handlePaymentCompleted(payment(order))
        assertEquals(OrderCancellationOutcome.PENDING,
            cancellation.cancelSellerOrderForEvent(order.id, canceled.id, "결제 후 일부 취소"))
        deliverRequests()
        deliverDecisions()
        // 과거 만료 시각이 남은 자료도 판매자 상태를 재검사해야 한다.
        JdbcTemplate(dataSource).update("update orders set expires_at = ? where id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(60)), order.id)
        assertFalse(expiration.expireOrder(order.id))
        service.handlePaymentCompleted(payment(order))
        assertEquals(OrdersStatus.ORDER_PARTIALLY_CANCELED, current(order).status)
        assertInventory(canceled, InventoryReservationState.RESTOCKED, 10)
        assertInventory(kept, InventoryReservationState.COMMITTED, 8)
        assertEquals(1, events("ORDER_CONFIRMED").size)
        assertEquals(1, events("ORDER_CANCELED").size)
    }

    @Test
    fun `만료 후보 조회 뒤 확정된 주문은 만료 호출이 취소하지 않는다`() {
        val order = reservedOrder(expired = true)
        val candidates = orders.findByStatusInAndExpiresAtBefore(
            listOf(OrdersStatus.ORDER_RESERVED, OrdersStatus.ORDER_PARTIALLY_CANCELED), Instant.now())
        assertEquals(listOf(order.id), candidates.map { it.id })
        service.handlePaymentCompleted(payment(order))
        assertFalse(expiration.expireOrder(candidates.single().id))
        assertConfirmed(order)
    }

    @Test
    fun `만료 후보 조회 뒤 갱신된 만료 시각과 null은 처리 시점에 다시 검사한다`() {
        val order = reservedOrder(expired = true)
        val jdbc = JdbcTemplate(dataSource)
        jdbc.update("update orders set expires_at = ? where id = ?",
            java.sql.Timestamp.from(Instant.now().plusSeconds(600)), order.id)
        assertFalse(expiration.expireOrder(order.id))
        jdbc.update("update orders set expires_at = null where id = ?", order.id)
        assertFalse(expiration.expireOrder(order.id))
        assertEquals(OrdersStatus.ORDER_RESERVED, current(order).status)
        order.sellerOrders.forEach { assertInventory(it, InventoryReservationState.RESERVED, 8) }
        assertEquals(0, outbox.count())
    }

    @Test
    fun `만료 후 늦은 결제와 중복은 취소된 주문이나 재고를 되살리지 않는다`() {
        val order = reservedOrder(expired = true)
        assertTrue(expiration.expireOrder(order.id))
        assertFalse(expiration.expireOrder(order.id))
        val message = payment(order)
        service.handlePaymentCompleted(message)
        service.handlePaymentCompleted(message)
        service.handlePaymentCompleted(payment(order))
        assertCanceled(order)
        assertEquals(1, events("ORDER_CANCELED").size)
        assertTrue(events("ORDER_CONFIRMED").isEmpty())
    }

    @Test
    fun `전부 취소 후 늦은 결제는 확정과 배송 이벤트를 만들지 않는다`() {
        val order = reservedOrder()
        assertTrue(cancellation.cancel(order.id, "전부 취소"))
        assertFalse(cancellation.cancel(order.id, "중복 취소"))
        service.handlePaymentCompleted(payment(order))
        service.handlePaymentCompleted(payment(order))
        assertCanceled(order)
        assertTrue(events("ORDER_CONFIRMED").isEmpty())
        assertEquals(1, events("ORDER_CANCELED").size)
    }

    @Test
    fun `부분 취소 후 만료는 아직 예약된 대상만 취소한다`() {
        val order = reservedOrder(expired = true)
        val canceled = order.sellerOrders.first()
        val kept = order.sellerOrders.last()
        assertTrue(cancellation.cancelSellerOrder(order.id, canceled.id, "일부 취소"))
        assertTrue(expiration.expireOrder(order.id))
        val payloads = events("ORDER_CANCELED").map { mapper.readValue(it.payload, OrderCanceledPayload::class.java) }
        assertEquals(setOf(canceled.id, kept.id), payloads.flatMap { it.canceledSellerOrders }.map { it.sellerOrderId }.toSet())
        assertEquals(2, payloads.sumOf { it.canceledSellerOrders.size })
        service.handlePaymentCompleted(payment(order))
        assertCanceled(order)
    }

    @RepeatedTest(3)
    fun `동시 확정과 만료는 하나의 상태와 일치하는 재고만 커밋한다`() {
        val order = reservedOrder(expired = true)
        concurrently({ service.handlePaymentCompleted(payment(order)) }, { expiration.expireOrder(order.id) })
        when (current(order).status) {
            OrdersStatus.ORDER_CONFIRMED -> {
                assertConfirmed(order)
                assertTrue(events("ORDER_CANCELED").isEmpty())
            }
            OrdersStatus.ORDER_CANCELED -> {
                assertCanceled(order)
                assertTrue(events("ORDER_CONFIRMED").isEmpty())
                assertEquals(1, events("ORDER_CANCELED").size)
            }
            else -> fail("확정 또는 만료 취소 중 한 결과만 남아야 한다")
        }
    }

    @RepeatedTest(3)
    fun `동시 부분 취소와 결제 확정은 취소 항목을 재확정하지 않는다`() {
        val order = reservedOrder()
        val (canceled, kept) = order.sellerOrders
        concurrently({ service.handlePaymentCompleted(payment(order)) },
            { assertTrue(cancellation.cancelSellerOrderForEvent(order.id, canceled.id, "동시 일부 취소") in
                setOf(OrderCancellationOutcome.CANCELED, OrderCancellationOutcome.PENDING)) })
        deliverRequests()
        deliverDecisions()
        assertEquals(OrdersStatus.ORDER_PARTIALLY_CANCELED, current(order).status)
        assertInventory(kept, InventoryReservationState.COMMITTED, 8)
        val state = inventory.getReservationMetadata(canceled.items.single().productId.toString(),
            canceled.items.single().reservationId)!!.state
        assertTrue(state in listOf(InventoryReservationState.RELEASED, InventoryReservationState.RESTOCKED))
        assertEquals(10, inventory.getAvailableStock(canceled.items.single().productId.toString()))
        assertEquals(1, events("ORDER_CANCELED").size)
        assertEquals(1, events("ORDER_CONFIRMED").size)
        val actual = current(order)
        assertEquals(mapOf(canceled.id to SellerOrderStatus.CANCELED, kept.id to SellerOrderStatus.CONFIRMED),
            actual.sellerOrders.associate { it.id to it.status })
    }

    @RepeatedTest(3)
    fun `서로 다른 결제 eventId가 경합해도 확정 효과는 한 번이다`() {
        val order = reservedOrder()
        concurrently({ service.handlePaymentCompleted(payment(order)) }, { service.handlePaymentCompleted(payment(order)) })
        assertConfirmed(order)
        assertEquals(2, markers.count())
    }

    @Test
    fun `확정이 잠금을 보유하면 만료는 실제 DB에서 대기한 뒤 확정을 보존한다`() {
        assertLockSerialization(confirmFirst = true)
    }

    @Test
    fun `만료가 잠금을 보유하면 확정은 실제 DB에서 대기한 뒤 취소를 보존한다`() {
        assertLockSerialization(confirmFirst = false)
    }

    @Test
    fun `확정 이벤트 직렬화 실패는 DB와 marker를 롤백하고 같은 이벤트로 재시도한다`() {
        val order = reservedOrder()
        val message = payment(order)
        doThrow(object : JsonProcessingException("확정 payload 직렬화 실패") {})
            .`when`(mapper).writeValueAsString(isA(OrderConfirmedPayload::class.java))
        assertThrows(JsonProcessingException::class.java) { service.handlePaymentCompleted(message) }
        assertFalse(markers.existsById(message.eventId))
        assertEquals(0, outbox.count())
        assertEquals(OrdersStatus.ORDER_RESERVED, current(order).status)
        assertTrue(current(order).sellerOrders.all { it.status == SellerOrderStatus.RESERVED })
        doCallRealMethod().`when`(mapper).writeValueAsString(isA(OrderConfirmedPayload::class.java))
        service.handlePaymentCompleted(message)
        assertConfirmed(order)
        assertTrue(markers.existsById(message.eventId))
    }

    @Test
    fun `부분 확정 중 재고 실패는 기존 취소를 유지하고 새 보상 대상만 센다`() {
        val order = reservedOrder(sellerCount = 3)
        val (canceled, committed, failed) = order.sellerOrders
        assertTrue(cancellation.cancelSellerOrder(order.id, canceled.id, "기존 취소"))
        val attempts = AtomicInteger()
        listOf(committed, failed).forEach { seller ->
            val item = seller.items.single()
            doAnswer { invocation ->
                if (attempts.incrementAndGet() == 1) invocation.callRealMethod()
                else InventoryReservationActionResult.NOT_FOUND
            }.`when`(inventory).commit(item.productId.toString(), item.reservationId, item.quantity)
        }
        service.handlePaymentCompleted(payment(order))
        assertEquals(OrdersStatus.ORDER_CANCELED, current(order).status)
        assertInventory(canceled, InventoryReservationState.RELEASED, 10)
        val activeStates = listOf(committed, failed).map { seller ->
            val item = seller.items.single()
            assertEquals(10, inventory.getAvailableStock(item.productId.toString()))
            inventory.getReservationMetadata(item.productId.toString(), item.reservationId)!!.state
        }
        assertEquals(setOf(InventoryReservationState.RESTOCKED, InventoryReservationState.RELEASED), activeStates.toSet())
        assertTrue(events("ORDER_CONFIRMED").isEmpty())
        assertEquals(2, sagas.findByOrderId(order.id)!!.expectedRefundCount)
        assertEquals(OrderSagaStatus.REFUND_PENDING, sagas.findByOrderId(order.id)!!.status)
    }

    @ParameterizedTest(name = "복구={0}, 전체 재고 복원 후 실패={1}")
    @CsvSource(
        "SAME_EVENT,true", "DIFFERENT_EVENT,true", "EXPIRE,true", "CANCEL,true", "SELLER_CANCEL,true",
        "SAME_EVENT,false", "DIFFERENT_EVENT,false", "EXPIRE,false", "CANCEL,false", "SELLER_CANCEL,false"
    )
    fun `보상 기록 롤백 후 결제 재시도와 만료 및 취소는 재고를 중복 복원하지 않고 수렴한다`(
        recovery: String, failAfterAllInventory: Boolean
    ) {
        val order = reservedOrder(expired = true, sellerCount = 3)
        val canceled = order.sellerOrders.first()
        val active = order.sellerOrders.drop(1)
        assertTrue(cancellation.cancelSellerOrder(order.id, canceled.id, "기존 취소"))
        val priorEvents = outbox.findAll().map { it.id }.toSet()
        val message = payment(order)
        val attempts = AtomicInteger()
        active.forEach { seller ->
            val item = seller.items.single()
            doAnswer { invocation ->
                if (attempts.incrementAndGet() == 1) invocation.callRealMethod()
                else InventoryReservationActionResult.NOT_FOUND
            }.`when`(inventory).commit(item.productId.toString(), item.reservationId, item.quantity)
        }
        val failedPayload = if (failAfterAllInventory) OrderCanceledPayload::class.java
            else InventoryStockAdjustedPayload::class.java
        doThrow(object : JsonProcessingException("보상 기록 실패") {})
            .`when`(mapper).writeValueAsString(isA(failedPayload))

        assertThrows(JsonProcessingException::class.java) { service.handlePaymentCompleted(message) }
        val rolledBack = current(order)
        assertEquals(OrdersStatus.ORDER_PARTIALLY_CANCELED, rolledBack.status)
        assertTrue(rolledBack.sellerOrders.filter { it.id != canceled.id }.all { seller ->
            seller.status == SellerOrderStatus.RESERVED && seller.items.all { it.status == OrderItemStatus.RESERVED }
        })
        assertFalse(markers.existsById(message.eventId))
        assertNull(sagas.findByOrderId(order.id))
        assertEquals(priorEvents, outbox.findAll().map { it.id }.toSet())
        val states = active.map { seller ->
            val item = seller.items.single()
            inventory.getReservationMetadata(item.productId.toString(), item.reservationId)!!.state
        }.toSet()
        assertEquals(setOf(InventoryReservationState.RESTOCKED,
            if (failAfterAllInventory) InventoryReservationState.RELEASED else InventoryReservationState.RESERVED), states)

        doCallRealMethod().`when`(mapper).writeValueAsString(isA(failedPayload))
        active.forEach { seller ->
            val item = seller.items.single()
            doCallRealMethod().`when`(inventory).commit(item.productId.toString(), item.reservationId, item.quantity)
        }
        when (recovery) {
            "SAME_EVENT" -> service.handlePaymentCompleted(message)
            "DIFFERENT_EVENT" -> {
                val republished = message.copy(eventId = UUID.randomUUID())
                service.handlePaymentCompleted(republished)
                assertTrue(markers.existsById(republished.eventId))
            }
            "EXPIRE" -> assertTrue(expiration.expireOrder(order.id))
            "CANCEL" -> assertTrue(cancellation.cancel(order.id, "롤백 후 전체 취소"))
            "SELLER_CANCEL" -> active.forEach {
                assertTrue(cancellation.cancelSellerOrder(order.id, it.id, "롤백 후 판매자 취소"))
            }
            else -> fail("알 수 없는 복구 경로: $recovery")
        }
        if (recovery in setOf("SAME_EVENT", "DIFFERENT_EVENT")) {
            assertEquals(2, sagas.findByOrderId(order.id)!!.expectedRefundCount)
            assertEquals(OrderSagaStatus.REFUND_PENDING, sagas.findByOrderId(order.id)!!.status)
        }

        val recovered = current(order)
        assertEquals(OrdersStatus.ORDER_CANCELED, recovered.status)
        assertNull(recovered.expiresAt)
        assertTrue(recovered.sellerOrders.all { seller -> seller.status == SellerOrderStatus.CANCELED &&
            seller.items.all { it.status == OrderItemStatus.CANCELED } })
        recovered.sellerOrders.forEach { seller ->
            val item = seller.items.single()
            val metadata = inventory.getReservationMetadata(item.productId.toString(), item.reservationId)!!
            assertTrue(metadata.state in setOf(InventoryReservationState.RELEASED, InventoryReservationState.RESTOCKED))
            assertEquals(10, inventory.getAvailableStock(item.productId.toString()))
            assertEquals(0, inventory.getStockSnapshot(item.productId.toString()).heldQuantity)
        }
        val refunded = events("ORDER_CANCELED").flatMap {
            mapper.readValue(it.payload, OrderCanceledPayload::class.java).canceledSellerOrders
        }
        assertEquals(order.sellerOrders.associate { it.id to it.paymentAmount },
            refunded.associate { it.sellerOrderId to it.refundAmount })
        assertEquals(3, refunded.size)
        val stockEvents = events("INVENTORY_STOCK_ADJUSTED").map {
            mapper.readValue(it.payload, InventoryStockAdjustedPayload::class.java)
        }
        assertEquals(3, stockEvents.size)
        order.sellerOrders.flatMap { it.items }.forEach { item ->
            val event = stockEvents.single { it.reservationId == item.reservationId }
            assertEquals(item.productId, event.productId)
            assertEquals(item.quantity, event.stockDelta)
            assertEquals(inventory.getReservationMetadata(item.productId.toString(), item.reservationId)!!.state.name,
                event.reason)
        }
        val completedEvents = outbox.findAll().map { it.id }.toSet()
        service.handlePaymentCompleted(message)
        service.handlePaymentCompleted(message)
        service.handlePaymentCompleted(message.copy(eventId = UUID.randomUUID()))
        assertTrue(markers.existsById(message.eventId))
        assertFalse(expiration.expireOrder(order.id))
        assertFalse(cancellation.cancel(order.id, "중복 취소"))
        assertEquals(completedEvents, outbox.findAll().map { it.id }.toSet())
        assertTrue(events("ORDER_CONFIRMED").isEmpty())
        recovered.sellerOrders.forEach { seller ->
            assertEquals(10, inventory.getAvailableStock(seller.items.single().productId.toString()))
        }
    }

    @Test
    fun `확정 이벤트 기록 롤백 후 사용자 취소는 Redis에 남은 commit을 한 번만 복원한다`() {
        val order = reservedOrder()
        val message = payment(order)
        doThrow(object : JsonProcessingException("확정 이벤트 기록 실패") {})
            .`when`(mapper).writeValueAsString(isA(OrderConfirmedPayload::class.java))
        assertThrows(JsonProcessingException::class.java) { service.handlePaymentCompleted(message) }
        assertEquals(OrdersStatus.ORDER_RESERVED, current(order).status)
        order.sellerOrders.forEach { assertInventory(it, InventoryReservationState.COMMITTED, 8) }
        assertFalse(markers.existsById(message.eventId))
        assertEquals(0, outbox.count())

        doCallRealMethod().`when`(mapper).writeValueAsString(isA(OrderConfirmedPayload::class.java))
        assertTrue(cancellation.cancel(order.id, "롤백 후 취소"))
        assertFalse(cancellation.cancel(order.id, "중복 취소"))
        service.handlePaymentCompleted(message)
        assertEquals(OrdersStatus.ORDER_CANCELED, current(order).status)
        order.sellerOrders.forEach { assertInventory(it, InventoryReservationState.RESTOCKED, 10) }
        assertEquals(2, events("INVENTORY_STOCK_ADJUSTED").size)
        assertEquals(1, events("ORDER_CANCELED").size)
        assertTrue(events("ORDER_CONFIRMED").isEmpty())
        assertTrue(markers.existsById(message.eventId))
    }

    @Test
    fun `확정 주문 취소의 기록 실패 후 재시도도 재고 이벤트를 복구한다`() {
        val order = reservedOrder()
        service.handlePaymentCompleted(payment(order))
        assertEquals(OrderCancellationOutcome.PENDING, cancellation.cancelForEvent(order.id, "최초 취소"))
        deliverRequests()
        val decision = events("SHIPPING_CANCELLATION_DECIDED").first()
        val payload = mapper.readValue(decision.payload, ShippingCancellationDecidedPayload::class.java)
        val target = order.sellerOrders.single { it.id == payload.sellerOrderId }
        val priorEvents = outbox.findAll().map { it.id }.toSet()
        doThrow(object : JsonProcessingException("취소 이벤트 기록 실패") {})
            .`when`(mapper).writeValueAsString(isA(OrderCanceledPayload::class.java))
        assertThrows(JsonProcessingException::class.java) { consumeDecision(decision) }
        assertFalse(markers.existsById(decision.id))
        assertEquals(OrdersStatus.ORDER_CONFIRMED, current(order).status)
        assertEquals(priorEvents, outbox.findAll().map { it.id }.toSet())
        assertInventory(target, InventoryReservationState.RESTOCKED, 10)
        assertTrue(current(order).sellerOrders.all { it.cancellationStatus == CancellationStatus.PENDING })

        doCallRealMethod().`when`(mapper).writeValueAsString(isA(OrderCanceledPayload::class.java))
        deliverDecisions()
        deliverDecisions()
        assertFalse(cancellation.cancel(order.id, "중복 취소"))
        assertEquals(OrdersStatus.ORDER_CANCELED, current(order).status)
        order.sellerOrders.forEach { assertInventory(it, InventoryReservationState.RESTOCKED, 10) }
        assertEquals(2, events("INVENTORY_STOCK_ADJUSTED").size)
        assertEquals(2, events("ORDER_CANCELED").size)
    }

    @Test
    fun `확정 재고 실패 보상은 다른 취소가 Redis 잠금을 보유해도 DB Redis 역전 없이 끝난다`() {
        val order = reservedOrder()
        order.sellerOrders.forEach { seller ->
            val item = seller.items.single()
            doReturn(InventoryReservationActionResult.NOT_FOUND).`when`(inventory)
                .commit(item.productId.toString(), item.reservationId, item.quantity)
        }
        val lock = redisson.getLock("order:cancel:${order.id}")
        val pool = Executors.newSingleThreadExecutor()
        lock.lock()
        try {
            pool.submit { service.handlePaymentCompleted(payment(order)) }.get(5, TimeUnit.SECONDS)
            assertCanceled(order)
        } finally {
            lock.unlock()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `만료는 행 잠금 대기 중 연장된 만료 시각을 최신 값으로 재검사한다`() {
        assertLockSerialization(confirmFirst = true, extendDeadline = true)
    }

    @Test
    fun `준비 중 취소는 승인까지 재고 환불을 보류하고 중복 요청과 응답은 단일 효과다`() {
        val order = confirmedWithShipping()
        val target = order.sellerOrders.first()
        assertEquals(OrderCancellationOutcome.PENDING, service.cancelSellerOrder(order.id, target.id, order.userId))
        assertEquals(OrderCancellationOutcome.PENDING, service.cancelSellerOrder(order.id, target.id, order.userId))
        assertInventory(target, InventoryReservationState.COMMITTED, 8)
        assertTrue(events("ORDER_CANCELED").isEmpty())
        assertTrue(events("INVENTORY_STOCK_ADJUSTED").isEmpty())
        assertEquals(CancellationStatus.PENDING, service.getOrder(order.id, order.userId).sellerOrders
            .single { it.sellerOrderId == target.id }.cancellationStatus)
        assertEquals(1, events("SHIPPING_CANCELLATION_REQUESTED").size)
        deliverRequests()
        val request = events("SHIPPING_CANCELLATION_REQUESTED").single()
        shipping.handleCancellationRequested(UUID.randomUUID(), request.payload)
        assertEquals(ShippingStatus.CANCELED, shipments.findAll().single { it.sellerOrderId == target.id }.status)
        assertInventory(target, InventoryReservationState.COMMITTED, 8)
        deliverDecisions()
        deliverDecisions()
        events("SHIPPING_CANCELLATION_DECIDED").forEach { consumeDecision(it, UUID.randomUUID()) }
        assertEquals(CancellationStatus.APPROVED, current(order).sellerOrders.single { it.id == target.id }.cancellationStatus)
        assertInventory(target, InventoryReservationState.RESTOCKED, 10)
        assertEquals(1, events("ORDER_CANCELED").size)
        assertEquals(listOf(target.id), mapper.readValue(events("ORDER_CANCELED").single().payload,
            OrderCanceledPayload::class.java).canceledSellerOrders.map { it.sellerOrderId })
        assertEquals(1, events("INVENTORY_STOCK_ADJUSTED").size)
        val kept = shipments.findAll().single { it.sellerOrderId != target.id }
        shipping.startShipping(kept.id)
        assertEquals(ShippingStatus.STARTED, shipments.findById(kept.id).orElseThrow().status)
    }

    @Test
    fun `취소가 확정보다 먼저 도착하면 늦은 확정과 worker는 배송을 되살리지 않는다`() {
        val order = reservedOrder(sellerCount = 1)
        service.handlePaymentCompleted(payment(order))
        assertEquals(OrderCancellationOutcome.PENDING, service.cancelOrder(order.id, order.userId))
        deliverRequests()
        val tombstone = shipments.findAll().single()
        assertEquals(ShippingStatus.CANCELED, tombstone.status)
        val confirmed = events("ORDER_CONFIRMED").single()
        repeat(2) { shipping.handleOrderConfirmed(UUID.randomUUID(), confirmed.payload) }
        shipping.startShipping(tombstone.id)
        completion.complete(tombstone.id)
        failure.fail(tombstone.id, IllegalStateException("지연 worker"))
        deliverDecisions()
        assertEquals(1, shipments.count())
        assertEquals(ShippingStatus.CANCELED, shipments.findAll().single().status)
        assertTrue(events("SHIPPING_STARTED").isEmpty())
        assertTrue(events("SHIPPING_COMPLETED").isEmpty())
        assertTrue(events("SHIPPING_FAILED").isEmpty())
        assertInventory(order.sellerOrders.single(), InventoryReservationState.RESTOCKED, 10)
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ShippingStatus::class, names = ["STARTED", "COMPLETED", "FAILED"])
    fun `출고 이후 취소와 배송 실패 보상은 환불이나 판매 재고를 복원하지 않는다`(state: ShippingStatus) {
        val order = confirmedWithShipping(1)
        val shipment = shipments.findAll().single()
        shipping.startShipping(shipment.id)
        when (state) {
            ShippingStatus.COMPLETED -> completion.complete(shipment.id)
            ShippingStatus.FAILED -> failure.fail(shipment.id, IllegalStateException("출고 후 배송 실패"))
            else -> Unit
        }
        if (state == ShippingStatus.FAILED) {
            val failed = events("SHIPPING_FAILED").single()
            ShippingEventsConsumer(mapper, service).receive(mapper.writeValueAsString(message(failed)))
        } else assertEquals(OrderCancellationOutcome.PENDING, service.cancelOrder(order.id, order.userId))
        deliverRequests()
        deliverDecisions()
        assertEquals(OrderCancellationOutcome.REJECTED, service.cancelOrder(order.id, order.userId))
        assertEquals(CancellationStatus.REJECTED, current(order).sellerOrders.single().cancellationStatus)
        assertEquals(state, shipments.findAll().single().status)
        assertEquals(OrdersStatus.ORDER_CONFIRMED, current(order).status)
        assertInventory(order.sellerOrders.single(), InventoryReservationState.COMMITTED, 8)
        assertTrue(events("ORDER_CANCELED").isEmpty())
        assertTrue(events("INVENTORY_STOCK_ADJUSTED").isEmpty())
        assertEquals(0, sagas.findByOrderId(order.id)!!.expectedRefundCount)
    }

    @RepeatedTest(3)
    fun `출고와 취소 경합은 배송 유지 거절 또는 배송 차단 승인 중 하나만 남긴다`() {
        val order = confirmedWithShipping(1)
        val shipment = shipments.findAll().single()
        assertEquals(OrderCancellationOutcome.PENDING, service.cancelOrder(order.id, order.userId))
        val request = events("SHIPPING_CANCELLATION_REQUESTED").single()
        concurrently({ shipping.startShipping(shipment.id) },
            { shipping.handleCancellationRequested(request.id, request.payload) })
        deliverDecisions()
        val actual = shipments.findAll().single()
        if (actual.status == ShippingStatus.CANCELED) {
            assertInventory(order.sellerOrders.single(), InventoryReservationState.RESTOCKED, 10)
            assertEquals(1, events("ORDER_CANCELED").size)
            assertTrue(events("SHIPPING_STARTED").isEmpty())
        } else {
            assertEquals(ShippingStatus.STARTED, actual.status)
            assertInventory(order.sellerOrders.single(), InventoryReservationState.COMMITTED, 8)
            assertEquals(CancellationStatus.REJECTED, current(order).sellerOrders.single().cancellationStatus)
            assertTrue(events("ORDER_CANCELED").isEmpty())
        }
    }

    @RepeatedTest(3)
    fun `배송이 없을 때 확정 생성과 취소가 경합해도 취소 기록을 덮어쓰지 않는다`() {
        val order = reservedOrder(sellerCount = 1)
        service.handlePaymentCompleted(payment(order))
        service.cancelOrder(order.id, order.userId)
        val confirmed = events("ORDER_CONFIRMED").single()
        val request = events("SHIPPING_CANCELLATION_REQUESTED").single()
        concurrently({ shipping.handleOrderConfirmed(confirmed.id, confirmed.payload) },
            { shipping.handleCancellationRequested(request.id, request.payload) })
        deliverDecisions()
        assertEquals(1, shipments.count())
        assertEquals(ShippingStatus.CANCELED, shipments.findAll().single().status)
        assertInventory(order.sellerOrders.single(), InventoryReservationState.RESTOCKED, 10)
        assertEquals(1, events("ORDER_CANCELED").size)
    }

    @Test
    fun `동시 중복 취소는 같은 요청을 만들고 확정 이벤트를 다시 받아도 단일 효과다`() {
        val order = confirmedWithShipping(1)
        concurrently({ service.cancelOrder(order.id, order.userId) }, { service.cancelOrder(order.id, order.userId) })
        assertEquals(1, events("SHIPPING_CANCELLATION_REQUESTED").size)
        deliverRequests()
        deliverDecisions()
        val confirmed = events("ORDER_CONFIRMED").single()
        shipping.handleOrderConfirmed(UUID.randomUUID(), confirmed.payload)
        assertEquals(ShippingStatus.CANCELED, shipments.findAll().single().status)
        assertEquals(1, events("ORDER_CANCELED").size)
        assertInventory(order.sellerOrders.single(), InventoryReservationState.RESTOCKED, 10)
    }

    @Test
    fun `재기동 후 대기 취소를 조회해 같은 requestId로 재요청하고 유실 응답을 복구한다`() {
        val order = confirmedWithShipping(1)
        service.cancelOrder(order.id, order.userId)
        val first = events("SHIPPING_CANCELLATION_REQUESTED").single()
        deliverRequests()
        val lost = events("SHIPPING_CANCELLATION_DECIDED").single()
        outbox.deleteById(lost.id)
        JdbcTemplate(dataSource).update("update seller_orders set cancellation_requested_at = ? where order_id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(120)), order.id)
        ShippingCancellationRecoveryScheduler(sellerOrders, executor).recover()
        ShippingCancellationRecoveryScheduler(sellerOrders, executor).recover()
        val retry = events("SHIPPING_CANCELLATION_REQUESTED").single { it.id != first.id }
        assertEquals(mapper.readValue(first.payload, ShippingCancellationRequestedPayload::class.java).requestId,
            mapper.readValue(retry.payload, ShippingCancellationRequestedPayload::class.java).requestId)
        shipping.handleCancellationRequested(retry.id, retry.payload)
        deliverDecisions()
        assertEquals(CancellationStatus.APPROVED, current(order).sellerOrders.single().cancellationStatus)
        assertEquals(1, events("ORDER_CANCELED").size)
        assertInventory(order.sellerOrders.single(), InventoryReservationState.RESTOCKED, 10)
    }

    @Test
    fun `재기동 후 준비 배송은 출고 scheduler가 처리하고 취소 배송은 제외한다`() {
        val order = confirmedWithShipping()
        val canceled = order.sellerOrders.first()
        service.cancelSellerOrder(order.id, canceled.id, order.userId)
        deliverRequests()
        deliverDecisions()
        JdbcTemplate(dataSource).update("update shipping set created_at = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(60)))
        ShippingDispatchScheduler(shipments, shipping, 30_000).dispatch()
        ShippingDispatchScheduler(shipments, shipping, 30_000).dispatch()
        assertEquals(setOf(ShippingStatus.CANCELED, ShippingStatus.STARTED), shipments.findAll().map { it.status }.toSet())
        assertEquals(1, events("SHIPPING_STARTED").size)
    }

    @Test
    fun `요청 기록 실패는 보류 상태와 Outbox를 함께 롤백하고 재시도할 수 있다`() {
        val order = confirmedWithShipping(1)
        doThrow(object : JsonProcessingException("요청 기록 실패") {})
            .`when`(mapper).writeValueAsString(isA(ShippingCancellationRequestedPayload::class.java))
        assertThrows(JsonProcessingException::class.java) { service.cancelOrder(order.id, order.userId) }
        assertEquals(CancellationStatus.NONE, current(order).sellerOrders.single().cancellationStatus)
        assertNull(current(order).sellerOrders.single().cancellationRequestId)
        assertTrue(events("SHIPPING_CANCELLATION_REQUESTED").isEmpty())
        assertInventory(order.sellerOrders.single(), InventoryReservationState.COMMITTED, 8)
        doCallRealMethod().`when`(mapper).writeValueAsString(isA(ShippingCancellationRequestedPayload::class.java))
        assertEquals(OrderCancellationOutcome.PENDING, service.cancelOrder(order.id, order.userId))
        deliverRequests(); deliverDecisions()
        assertInventory(order.sellerOrders.single(), InventoryReservationState.RESTOCKED, 10)
    }

    @Test
    fun `배송 승인 기록 실패는 취소 tombstone과 marker를 롤백하고 같은 이벤트로 복구한다`() {
        val order = reservedOrder(sellerCount = 1)
        service.handlePaymentCompleted(payment(order))
        service.cancelOrder(order.id, order.userId)
        val request = events("SHIPPING_CANCELLATION_REQUESTED").single()
        doThrow(object : JsonProcessingException("결정 기록 실패") {})
            .`when`(mapper).writeValueAsString(isA(ShippingCancellationDecidedPayload::class.java))
        assertThrows(JsonProcessingException::class.java) { shipping.handleCancellationRequested(request.id, request.payload) }
        assertFalse(markers.existsById(request.id))
        assertEquals(0, shipments.count())
        assertTrue(events("SHIPPING_CANCELLATION_DECIDED").isEmpty())
        assertInventory(order.sellerOrders.single(), InventoryReservationState.COMMITTED, 8)
        doCallRealMethod().`when`(mapper).writeValueAsString(isA(ShippingCancellationDecidedPayload::class.java))
        shipping.handleCancellationRequested(request.id, request.payload)
        deliverDecisions()
        assertEquals(ShippingStatus.CANCELED, shipments.findAll().single().status)
        assertInventory(order.sellerOrders.single(), InventoryReservationState.RESTOCKED, 10)
    }

    @Test
    fun `요청과 다른 응답과 거절 후 반대 응답은 상태를 바꾸지 않고 marker도 롤백한다`() {
        val order = confirmedWithShipping(1)
        shipping.startShipping(shipments.findAll().single().id)
        service.cancelOrder(order.id, order.userId)
        deliverRequests()
        val event = events("SHIPPING_CANCELLATION_DECIDED").single()
        val payload = mapper.readValue(event.payload, ShippingCancellationDecidedPayload::class.java)
        val wrong = message(event).copy(eventId = UUID.randomUUID(),
            payload = mapper.writeValueAsString(payload.copy(requestId = UUID.randomUUID())))
        assertThrows(IllegalStateException::class.java) { service.handleShippingCancellationDecided(wrong) }
        assertFalse(markers.existsById(wrong.eventId))
        deliverDecisions()
        val conflict = message(event).copy(eventId = UUID.randomUUID(),
            payload = mapper.writeValueAsString(payload.copy(approved = true)))
        assertThrows(IllegalStateException::class.java) { service.handleShippingCancellationDecided(conflict) }
        assertFalse(markers.existsById(conflict.eventId))
        assertInventory(order.sellerOrders.single(), InventoryReservationState.COMMITTED, 8)
        assertTrue(events("ORDER_CANCELED").isEmpty())
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [true, false])
    fun `취소와 출고는 상대 트랜잭션의 실제 배송 행 잠금에서 대기한 뒤 결정한다`(cancelFirst: Boolean) {
        val order = confirmedWithShipping(1)
        val shipment = shipments.findAll().single()
        service.cancelOrder(order.id, order.userId)
        val request = events("SHIPPING_CANCELLATION_REQUESTED").single()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val pid = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        fun cancel() = shipping.handleCancellationRequested(request.id, request.payload)
        fun dispatch() = shipping.startShipping(shipment.id)
        try {
            val owner = pool.submit {
                tx().execute {
                    shipments.findByIdForUpdate(shipment.id)
                    if (cancelFirst) cancel() else dispatch()
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val contender = pool.submit {
                tx().execute {
                    pid.set(JdbcTemplate(dataSource).queryForObject("select pg_backend_pid()", Int::class.java)!!)
                    ready.countDown()
                    if (cancelFirst) dispatch() else cancel()
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var waiting = false
            while (!waiting && System.nanoTime() < deadline) {
                waiting = JdbcTemplate(dataSource).queryForObject(
                    "select exists(select 1 from pg_stat_activity where pid = ? and wait_event_type = 'Lock')",
                    Boolean::class.java, pid.get())!!
                if (!waiting) Thread.sleep(20)
            }
            assertTrue(waiting)
            assertFalse(contender.isDone)
            release.countDown()
            owner.get(15, TimeUnit.SECONDS)
            contender.get(15, TimeUnit.SECONDS)
            deliverDecisions()
            if (cancelFirst) {
                assertEquals(ShippingStatus.CANCELED, shipments.findAll().single().status)
                assertInventory(order.sellerOrders.single(), InventoryReservationState.RESTOCKED, 10)
                assertTrue(events("SHIPPING_STARTED").isEmpty())
            } else {
                assertEquals(ShippingStatus.STARTED, shipments.findAll().single().status)
                assertInventory(order.sellerOrders.single(), InventoryReservationState.COMMITTED, 8)
                assertEquals(CancellationStatus.REJECTED, current(order).sellerOrders.single().cancellationStatus)
                assertTrue(events("ORDER_CANCELED").isEmpty())
            }
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `출고 전 배송 실패는 취소 승인 후에만 환불 사가를 시작한다`() {
        val order = confirmedWithShipping(1)
        failure.fail(shipments.findAll().single().id, IllegalStateException("준비 중 오류"))
        val failed = events("SHIPPING_FAILED").single()
        ShippingEventsConsumer(mapper, service).receive(mapper.writeValueAsString(message(failed)))
        assertEquals(CancellationStatus.PENDING, current(order).sellerOrders.single().cancellationStatus)
        assertEquals(0, sagas.findByOrderId(order.id)!!.expectedRefundCount)
        assertTrue(events("ORDER_CANCELED").isEmpty())
        deliverRequests(); deliverDecisions(); deliverDecisions()
        assertEquals(OrderSagaStatus.REFUND_PENDING, sagas.findByOrderId(order.id)!!.status)
        assertEquals(1, sagas.findByOrderId(order.id)!!.expectedRefundCount)
        assertEquals(1, events("ORDER_CANCELED").size)
        assertEquals(ShippingStatus.CANCELED, shipments.findAll().single().status)
        assertInventory(order.sellerOrders.single(), InventoryReservationState.RESTOCKED, 10)
    }

    @Test
    fun `전체 취소에서 출고된 판매자는 거절하고 준비 중 판매자만 환불한다`() {
        val order = confirmedWithShipping()
        val dispatched = shipments.findAll().first()
        shipping.startShipping(dispatched.id)
        assertEquals(OrderCancellationOutcome.PENDING, service.cancelOrder(order.id, order.userId))
        deliverRequests(); deliverDecisions()
        assertEquals(OrderCancellationOutcome.REJECTED, service.cancelOrder(order.id, order.userId))
        val actual = current(order)
        assertEquals(OrdersStatus.ORDER_PARTIALLY_CANCELED, actual.status)
        val rejected = actual.sellerOrders.single { it.id == dispatched.sellerOrderId }
        val approved = actual.sellerOrders.single { it.id != dispatched.sellerOrderId }
        assertEquals(CancellationStatus.REJECTED, rejected.cancellationStatus)
        assertEquals(CancellationStatus.APPROVED, approved.cancellationStatus)
        assertInventory(rejected, InventoryReservationState.COMMITTED, 8)
        assertInventory(approved, InventoryReservationState.RESTOCKED, 10)
        val refund = mapper.readValue(events("ORDER_CANCELED").single().payload, OrderCanceledPayload::class.java)
        assertEquals(listOf(approved.id), refund.canceledSellerOrders.map { it.sellerOrderId })
        assertEquals(approved.paymentAmount, refund.canceledSellerOrders.single().refundAmount)
    }

    @Test
    fun `신규 스키마 SQL은 요청과 결정의 CHECK 및 UNIQUE를 실제 PostgreSQL에서 강제한다`() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute("create schema s4_schema_validation")
                    statement.execute("set local search_path to s4_schema_validation")
                    statement.execute("create table seller_orders (id uuid primary key, order_id uuid not null)")
                    statement.execute("create table shipping (id uuid primary key, status varchar(32) not null, created_at timestamptz not null)")
                    listOf("V4__shipping_cancellation_request.sql", "V3__shipping_cancellation_decision.sql").forEach { file ->
                        val source = requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$file"))
                            .bufferedReader().use { it.readText() }
                        statement.execute(source)
                    }
                    val a = UUID.randomUUID()
                    val b = UUID.randomUUID()
                    val request = UUID.randomUUID()
                    statement.execute("insert into seller_orders(id,order_id) values ('$a','$a'),('$b','$a')")
                    fun rejects(expected: String, sql: String) {
                        val savepoint = connection.setSavepoint()
                        val error = assertThrows(java.sql.SQLException::class.java) { statement.execute(sql) }
                        assertEquals(expected, error.sqlState)
                        connection.rollback(savepoint)
                    }
                    rejects("23514", "update seller_orders set cancellation_status='PENDING' where id='$a'")
                    statement.execute("update seller_orders set cancellation_status='PENDING', cancellation_request_id='$request', cancellation_reason='취소', cancellation_requested_at=now() where id='$a'")
                    rejects("23505", "update seller_orders set cancellation_status='PENDING', cancellation_request_id='$request', cancellation_reason='취소', cancellation_requested_at=now() where id='$b'")
                    statement.execute("insert into shipping(id,status,created_at) values ('$a','PREPARING',now()),('$b','STARTED',now())")
                    rejects("23514", "update shipping set cancellation_request_id='$request' where id='$a'")
                    rejects("23514", "update shipping set cancellation_request_id='$request', cancellation_approved=true where id='$b'")
                    statement.execute("update shipping set status='CANCELED', cancellation_request_id='$request', cancellation_approved=true where id='$a'")
                    rejects("23505", "update shipping set cancellation_request_id='$request', cancellation_approved=false where id='$b'")
                }
            } finally {
                connection.rollback()
            }
        }
    }

    private fun confirmedWithShipping(sellerCount: Int = 2): Orders {
        val order = reservedOrder(sellerCount = sellerCount)
        service.handlePaymentCompleted(payment(order))
        val event = events("ORDER_CONFIRMED").single()
        PaymentEventsConsumer(mapper, shipping).receive(mapper.writeValueAsString(message(event)))
        return order
    }

    private fun deliverRequests() {
        events("SHIPPING_CANCELLATION_REQUESTED").forEach {
            PaymentEventsConsumer(mapper, shipping).receive(mapper.writeValueAsString(message(it)))
        }
    }

    private fun deliverDecisions() = events("SHIPPING_CANCELLATION_DECIDED").forEach { consumeDecision(it) }
    private fun consumeDecision(event: OutboxEvent, eventId: UUID = event.id) {
        ShippingEventsConsumer(mapper, service).receive(mapper.writeValueAsString(message(event).copy(eventId = eventId)))
    }
    private fun message(event: OutboxEvent) = OutboxEventMessage(event.id, event.aggregateType,
        event.aggregateId, event.eventType, event.createdAt, event.payload)

    private fun assertLockSerialization(confirmFirst: Boolean, extendDeadline: Boolean = false) {
        val order = reservedOrder(expired = true)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val pid = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner = pool.submit {
                tx().execute {
                    val lockedOrder = orders.findByIdForUpdate(order.id)!!
                    if (extendDeadline) lockedOrder.expiresAt = Instant.now().plusSeconds(600)
                    else if (confirmFirst) service.handlePaymentCompleted(payment(order))
                    else expiration.expireOrder(order.id)
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val contender = pool.submit {
                tx().execute {
                    pid.set(JdbcTemplate(dataSource).queryForObject("select pg_backend_pid()", Int::class.java)!!)
                    ready.countDown()
                    if (confirmFirst) expiration.expireOrder(order.id) else service.handlePaymentCompleted(payment(order))
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var waiting = false
            while (!waiting && System.nanoTime() < deadline) {
                waiting = JdbcTemplate(dataSource).queryForObject(
                    "select exists(select 1 from pg_stat_activity where pid = ? and wait_event_type = 'Lock')",
                    Boolean::class.java, pid.get())!!
                if (!waiting) Thread.sleep(20)
            }
            assertTrue(waiting, "경합 트랜잭션은 PostgreSQL 주문 행 잠금에서 대기해야 한다")
            assertFalse(contender.isDone)
            release.countDown()
            owner.get(15, TimeUnit.SECONDS)
            contender.get(15, TimeUnit.SECONDS)
            if (extendDeadline) {
                assertEquals(OrdersStatus.ORDER_RESERVED, current(order).status)
                order.sellerOrders.forEach { assertInventory(it, InventoryReservationState.RESERVED, 8) }
                assertEquals(0, outbox.count())
            } else if (confirmFirst) assertConfirmed(order) else assertCanceled(order)
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun reservedOrder(expired: Boolean = false, sellerCount: Int = 2): Orders = tx().execute {
        val order = Orders(UUID.randomUUID(), status = OrdersStatus.ORDER_RESERVED,
            expiresAt = Instant.now().plusSeconds(if (expired) -60 else 600))
        repeat(sellerCount) { index ->
            val seller = SellerOrder(order, UUID.randomUUID(), 10_000L * (index + 1),
                paymentAmount = 10_000L * (index + 1), commissionRate = 0.1,
                commissionAmount = 1_000L * (index + 1), settlementAmount = 9_000L * (index + 1))
            seller.addItem(OrderItem(seller, UUID.randomUUID(), "상품 $index", 2, 5_000L * (index + 1),
                10_000L * (index + 1), UUID.randomUUID()))
            order.addSellerOrder(seller)
        }
        order.recomputeTotals()
        orders.saveAndFlush(order)
        order.sellerOrders.forEach { seller -> seller.items.forEach { item ->
            inventory.initializeStock(item.productId.toString(), 10)
            assertEquals(item.reservationId, inventory.reserve(item.productId.toString(), order.id, 2, 600, item.reservationId))
        } }
        order
    }!!

    private fun current(order: Orders): Orders = tx().execute {
        orders.findById(order.id).orElseThrow().also { o -> o.sellerOrders.forEach { it.items.size } }
    }!!
    private fun payment(order: Orders) = OutboxEventMessage(UUID.randomUUID(), "PAYMENT", order.id,
        "PAYMENT_COMPLETED", Instant.now(), mapper.writeValueAsString(PaymentCompletedPayload(
            UUID.randomUUID(), order.id, order.userId, order.totalPaymentAmount, completedAt = Instant.now())))
    private fun events(type: String) = outbox.findAll().filter { it.eventType == type }
    private fun tx() = TransactionTemplate(transactionManager)
    private fun assertInventory(seller: SellerOrder, state: InventoryReservationState, stock: Long) {
        val item = seller.items.single()
        assertEquals(state, inventory.getReservationMetadata(item.productId.toString(), item.reservationId)!!.state)
        assertEquals(stock, inventory.getAvailableStock(item.productId.toString()))
    }
    private fun assertConfirmed(order: Orders) {
        val actual = current(order)
        assertEquals(OrdersStatus.ORDER_CONFIRMED, actual.status)
        assertNull(actual.expiresAt)
        assertTrue(actual.sellerOrders.all { it.status == SellerOrderStatus.CONFIRMED &&
            it.items.all { item -> item.status == OrderItemStatus.CONFIRMED } })
        actual.sellerOrders.forEach { assertInventory(it, InventoryReservationState.COMMITTED, 8) }
        assertEquals(1, events("ORDER_CONFIRMED").size)
    }
    private fun assertCanceled(order: Orders) {
        val actual = current(order)
        assertEquals(OrdersStatus.ORDER_CANCELED, actual.status)
        assertTrue(actual.sellerOrders.all { it.status == SellerOrderStatus.CANCELED &&
            it.items.all { item -> item.status == OrderItemStatus.CANCELED } })
        actual.sellerOrders.forEach { assertInventory(it, InventoryReservationState.RELEASED, 10) }
    }
    private fun concurrently(vararg actions: () -> Unit) {
        val barrier = CyclicBarrier(actions.size)
        val pool = Executors.newFixedThreadPool(actions.size)
        try {
            actions.map { action -> pool.submit {
                tx().execute {
                    JdbcTemplate(dataSource).queryForObject("select 1", Int::class.java)
                    barrier.await(10, TimeUnit.SECONDS)
                    action()
                }
            } }.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        @Container @JvmStatic val redisContainer = GenericContainer<Nothing>("redis:7.2-alpine").apply { withExposedPorts(6379) }
        @DynamicPropertySource @JvmStatic fun registerPostgres(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
@EntityScan(basePackageClasses = [Orders::class, ProcessedEvent::class, OutboxEvent::class, Shipping::class])
@EnableJpaRepositories(basePackageClasses = [OrdersRepository::class, ProcessedEventRepository::class,
    OutboxEventRepository::class, ShippingRepository::class])
@Import(OrdersService::class, OrderCancelExecutor::class, OrderCancelService::class, OrderExpirationService::class,
    OrderSagaService::class, IdempotencyHandler::class, OutboxEventService::class, OutboxRetryPolicy::class,
    OutboxPublisherProperties::class, ShippingService::class, ShippingCompletionService::class, ShippingFailureService::class)
class OrderReservationTestConfiguration {
    @Bean fun businessMetrics(): EventfulBusinessMetrics = mock(EventfulBusinessMetrics::class.java)
    @Bean fun shippingCompletionWorker(): ShippingCompletionWorker = mock(ShippingCompletionWorker::class.java)
    @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    @Bean fun redisConnectionFactory(): LettuceConnectionFactory = LettuceConnectionFactory(
        RedisStandaloneConfiguration(OrderReservationPostgresIntegrationTest.redisContainer.host,
            OrderReservationPostgresIntegrationTest.redisContainer.getMappedPort(6379)))
    @Bean fun redisTemplate(factory: LettuceConnectionFactory) = StringRedisTemplate(factory)
    @Bean fun inventory(template: StringRedisTemplate) = InventoryReservationService(template, 3_600)
    @Bean(destroyMethod = "shutdown") fun redissonClient(): RedissonClient = Redisson.create(Config().apply {
        useSingleServer().address = "redis://${OrderReservationPostgresIntegrationTest.redisContainer.host}:" +
            OrderReservationPostgresIntegrationTest.redisContainer.getMappedPort(6379)
    })
}
