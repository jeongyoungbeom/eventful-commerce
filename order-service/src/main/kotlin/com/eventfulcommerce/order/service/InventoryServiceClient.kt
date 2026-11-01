package com.eventfulcommerce.order.service

import com.eventfulcommerce.order.domain.entity.AttemptItem
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RemoteReservation(val state: String, val reason: String? = null, val warehouseId: UUID? = null)

@Component
class InventoryServiceClient(
    private val mapper: ObjectMapper,
    @Value("\${order.direct-inventory.base-url:http://localhost:8088}") baseUrl: String,
    @Value("\${order.direct-inventory.token:}") private val token: String
) {
    private val base = URI.create(baseUrl.trimEnd('/')).also {
        require(it.scheme in listOf("http", "https") && it.host != null && it.userInfo == null && it.query == null && it.fragment == null)
    }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    fun resolve(orderId: UUID, item: AttemptItem, expiresAt: Instant): RemoteReservation? {
        var method = "GET"
        return retry {
            val response = call(method, orderId, item, expiresAt)
            if (response.statusCode() == 404 && method == "GET") {
                method = "PUT"
                null
            } else parse(response, orderId, item, expiresAt)
        }
    }

    fun release(orderId: UUID, item: AttemptItem, expiresAt: Instant): RemoteReservation? = retry {
        parse(call("POST", orderId, item, expiresAt), orderId, item, expiresAt)
            ?.takeIf { it.state in setOf("RELEASED", "EXPIRED", "REJECTED", "INVALIDATED", "CANCELED") }
    }

    fun allocate(orderId: UUID, item: AttemptItem, expiresAt: Instant): RemoteReservation? = retry {
        parse(call("POST", orderId, item, expiresAt, "/allocate"), orderId, item, expiresAt)
    }

    fun cancel(orderId: UUID, item: AttemptItem, expiresAt: Instant): RemoteReservation? = retry {
        parse(call("POST", orderId, item, expiresAt, "/cancel"), orderId, item, expiresAt)
    }

    private fun call(method: String, orderId: UUID, item: AttemptItem, expiresAt: Instant, action: String = ""): HttpResponse<String> {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "재고 HTTP는 DB 트랜잭션 밖에서 호출해야 한다" }
        require(token.isNotBlank()) { "재고 내부 인증이 구성되지 않았다" }
        val suffix = action.ifEmpty { if (method == "POST") "/release" else "" }
        val request = HttpRequest.newBuilder(URI.create("$base/internal/reservations/${item.reservationId}$suffix"))
            .timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
        if (method == "GET") request.GET() else request.method(method, HttpRequest.BodyPublishers.ofString(
            mapper.writeValueAsString(mapOf("orderAttemptId" to orderId, "orderItemId" to item.orderItemId,
                "skuId" to item.productId, "quantity" to item.quantity.toLong(), "expiresAt" to expiresAt))))
        // HttpRequest.timeout만으로는 헤더 수신 후 멈춘 본문 대기를 제한하지 못한다.
        val response = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
        return try { response.get(2, TimeUnit.SECONDS) }
        catch (e: Exception) { response.cancel(true); throw e }
    }

    private fun parse(response: HttpResponse<String>, orderId: UUID, item: AttemptItem, expiresAt: Instant): RemoteReservation? {
        if (response.statusCode() != 200) return null
        val root = mapper.readTree(response.body())
        val r = root.required("reservation")
        fun uuid(name: String) = UUID.fromString(r.required(name).asText())
        check(uuid("id") == item.reservationId && uuid("orderAttemptId") == orderId &&
            uuid("orderItemId") == item.orderItemId && uuid("skuId") == item.productId &&
            r.required("quantity").asLong() == item.quantity.toLong() &&
            Instant.parse(r.required("expiresAt").asText()) == expiresAt) { "재고 응답의 예약 원본 불일치" }
        val state = r.required("status").asText()
        check(state in setOf("RESERVED", "REJECTED", "RELEASED", "EXPIRED", "INVALIDATED", "ALLOCATED", "CANCELED", "SHIPPED", "RETURNED"))
        val warehouse = r.get("warehouseId")?.takeUnless(JsonNode::isNull)?.asText()?.let(UUID::fromString)
        if (state in setOf("RESERVED", "ALLOCATED")) {
            check(warehouse != null)
            val lines = root.required("lines")
            check(lines.isArray && lines.size() > 0)
            var quantity = 0L
            for (line in lines) {
                check(UUID.fromString(line.required("reservationId").asText()) == item.reservationId &&
                    UUID.fromString(line.required("skuId").asText()) == item.productId &&
                    UUID.fromString(line.required("warehouseId").asText()) == warehouse)
                val n = line.required("quantity").asLong()
                check(n > 0)
                quantity = Math.addExact(quantity, n)
            }
            check(quantity == item.quantity.toLong())
        }
        val cancellation = root.get("cancellationStatus")?.takeUnless(JsonNode::isNull)?.asText()
        check(cancellation == null || cancellation in setOf("PENDING", "REJECTED"))
        return RemoteReservation(state, cancellation ?: r.get("reason")?.takeUnless(JsonNode::isNull)?.asText(), warehouse)
    }

    private fun retry(action: () -> RemoteReservation?): RemoteReservation? {
        if (token.isBlank()) return null
        repeat(3) { index ->
            if (index > 0) try { Thread.sleep(if (index == 1) 250 else 500) }
                catch (e: InterruptedException) { Thread.currentThread().interrupt(); return null }
            try { action()?.let { return it } }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); return null }
            catch (_: Exception) { /* 결과 불명은 영속 미확인으로 보존하고 다음 복구 주기에 조회한다. */ }
        }
        return null
    }
}
