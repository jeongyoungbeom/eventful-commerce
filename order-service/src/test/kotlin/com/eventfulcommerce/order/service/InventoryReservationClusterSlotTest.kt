package com.eventfulcommerce.order.service

import io.lettuce.core.cluster.SlotHash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class InventoryReservationClusterSlotTest {

    @Test
    fun `every key used together by an inventory Lua script shares one Redis Cluster slot`() {
        val productId = UUID.randomUUID().toString()
        val reservationId = UUID.randomUUID()
        val stockKey = "{product:$productId}:stock"
        val holdKey = "{product:$productId}:hold:$reservationId"
        val holdCountKey = "{product:$productId}:holdCount"
        val expirationKey = "{product:$productId}:holdExpirations"
        val stockEventKey = "{product:$productId}:stock-event:${UUID.randomUUID()}"

        assertSingleSlot(listOf(stockKey, holdKey, holdCountKey, expirationKey)) // reserve/release
        assertSingleSlot(listOf(holdKey, holdCountKey, expirationKey)) // commit
        assertSingleSlot(listOf(stockKey, holdKey, expirationKey)) // restock
        assertSingleSlot(listOf(stockKey, stockEventKey)) // idempotent stock adjustment
    }

    private fun assertSingleSlot(keys: List<String>) {
        assertEquals(1, keys.map(SlotHash::getSlot).toSet().size, "Keys must share a Redis Cluster slot: $keys")
    }
}
