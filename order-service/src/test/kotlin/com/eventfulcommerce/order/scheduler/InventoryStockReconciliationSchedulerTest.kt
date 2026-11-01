package com.eventfulcommerce.order.scheduler

import com.eventfulcommerce.common.*
import com.eventfulcommerce.order.domain.entity.ProductReadModel
import com.eventfulcommerce.order.repository.ProductReadModelRepository
import com.eventfulcommerce.order.service.InventoryReservationService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.util.UUID

class InventoryStockReconciliationSchedulerTest {
    @Test fun `직매입은 Redis 대사에서 제외된다`() {
        val products = mockk<ProductReadModelRepository>()
        val inventory = mockk<InventoryReservationService>()
        val outbox = mockk<OutboxEventService>()
        every { products.findAll(any<Pageable>()) } returns PageImpl(listOf(ProductReadModel(
            UUID.randomUUID(), SalesType.OFFICIAL_SELLER_ID, "공식 상품", 100, 0, "FLOWERS", salesType = SalesType.DIRECT_PURCHASE)))
        InventoryStockReconciliationScheduler(products, inventory, outbox, jacksonObjectMapper(), 10).reconcileAvailableStock()
        verify { inventory wasNot Called }; verify { outbox wasNot Called }
    }
}
