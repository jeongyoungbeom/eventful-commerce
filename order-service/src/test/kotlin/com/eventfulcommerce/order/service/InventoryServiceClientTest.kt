package com.eventfulcommerce.order.service

import com.eventfulcommerce.common.SalesType
import com.eventfulcommerce.order.domain.entity.AttemptItem
import com.eventfulcommerce.order.domain.entity.AttemptItemState
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InventoryServiceClientTest {
    @Test
    fun `본문 시간 초과는 재시도한 세 HTTP 연결을 모두 취소한다`() {
        ServerSocket(0, 10, InetAddress.getLoopbackAddress()).use { server ->
            val pool = Executors.newCachedThreadPool()
            val sockets = CopyOnWriteArrayList<Socket>()
            val closed = CountDownLatch(3)
            val requests = CopyOnWriteArrayList<String>()
            val accepting = pool.submit {
                repeat(3) {
                    val socket = server.accept().also(sockets::add)
                    pool.submit {
                        socket.use {
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            requests.add(input.readLine())
                            while (!input.readLine().isNullOrEmpty()) { }
                            socket.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{".toByteArray(Charsets.US_ASCII))
                            socket.getOutputStream().flush()
                            if (input.read() == -1) closed.countDown()
                        }
                    }
                }
            }
            try {
                val client = InventoryServiceClient(ObjectMapper(), "http://127.0.0.1:${server.localPort}", "test-token")
                val item = AttemptItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "직매입", SalesType.OFFICIAL_SELLER_ID, SalesType.DIRECT_PURCHASE, 1, 100, AttemptItemState.PENDING)
                assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                    assertNull(client.resolve(UUID.randomUUID(), item, Instant.now().plusSeconds(600)))
                }
                accepting.get(1, TimeUnit.SECONDS)
                assertTrue(closed.await(2, TimeUnit.SECONDS), "시간 초과 후 미완료 HTTP 연결이 남았다")
                assertEquals(3, requests.size)
                assertTrue(requests.all { it == "GET /internal/reservations/${item.reservationId} HTTP/1.1" })
            } finally {
                server.close()
                sockets.forEach { it.close() }
                pool.shutdownNow()
            }
        }
    }
}
