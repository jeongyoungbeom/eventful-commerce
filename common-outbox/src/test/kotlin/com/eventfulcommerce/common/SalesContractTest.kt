package com.eventfulcommerce.common

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SalesContractTest {
    private val mapper = jacksonObjectMapper()
    @Test fun `이전 JSON은 입점으로 읽히고 새 JSON은 직매입을 구분한다`() {
        val id = UUID.randomUUID()
        val old = """{"productId":"$id","sellerId":"${UUID.randomUUID()}","name":"공식몰","price":100,"initialStock":2,"category":"FLOWERS"}"""
        val legacy = mapper.readValue(old, ProductRegisteredPayload::class.java)
        assertEquals(SalesType.MARKETPLACE, legacy.salesType)
        assertEquals(id, legacy.skuId)
        assertEquals(1, legacy.schemaVersion)
        val direct = legacy.copy(sellerId = SalesType.OFFICIAL_SELLER_ID, salesType = SalesType.DIRECT_PURCHASE, schemaVersion = 2)
        assertEquals(direct, mapper.readValue(mapper.writeValueAsString(direct), ProductRegisteredPayload::class.java))
        assertEquals(InventorySource.INVENTORY_SERVICE, direct.salesType.inventorySource)
    }
    @Test fun `공식 ID 도용 SKU 불일치 미래 버전과 판매방식 누락을 거부한다`() {
        val product = ProductRegisteredPayload(UUID.randomUUID(), UUID.randomUUID(), "상품", 100, 1, "FLOWERS")
        assertThrows(IllegalArgumentException::class.java) { product.copy(sellerId = SalesType.OFFICIAL_SELLER_ID) }
        assertThrows(IllegalArgumentException::class.java) { product.copy(salesType = SalesType.DIRECT_PURCHASE, schemaVersion = 2) }
        assertThrows(IllegalArgumentException::class.java) { product.copy(skuId = UUID.randomUUID()) }
        assertThrows(IllegalArgumentException::class.java) { product.copy(schemaVersion = 3) }
        assertThrows(IllegalArgumentException::class.java) { product.copy(sellerId = SalesType.OFFICIAL_SELLER_ID, salesType = SalesType.DIRECT_PURCHASE) }
    }
}
