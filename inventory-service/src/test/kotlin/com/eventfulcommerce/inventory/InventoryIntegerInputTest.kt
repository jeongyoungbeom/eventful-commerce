package com.eventfulcommerce.inventory

import com.eventfulcommerce.inventory.controller.*
import com.eventfulcommerce.inventory.dto.*
import com.eventfulcommerce.inventory.service.*
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.*
import org.springframework.http.MediaType
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.http.HttpMethod
import java.util.UUID

class InventoryIntegerInputTest {
    private val mapper = Jackson2ObjectMapperBuilder.json().build<com.fasterxml.jackson.databind.ObjectMapper>()
    private val inventory = mock(InventoryService::class.java)
    private val reservations = mock(ReservationService::class.java)
    private val fulfillment = mock(FulfillmentService::class.java)
    private val transfers = mock(TransferService::class.java)
    private val counts = mock(StockCountService::class.java)
    private val mvc = MockMvcBuilders.standaloneSetup(InventoryController(inventory), ReservationController(reservations),
        FulfillmentController(fulfillment), TransferController(transfers), StockCountController(counts))
        .setControllerAdvice(InventoryExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper)).build()

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputs")
    fun `모든 재고 수량과 원가는 잘못된 JSON을 서비스 호출 전에 400으로 거부한다`(input: Input) {
        for (raw in listOf("null", "1.9", "1e0", "\"1\"", "true", "[]", "{}", "-1", "9223372036854775808", null)) {
            val body = mapper.readTree(input.body) as ObjectNode
            val parent = body.at(input.parent) as ObjectNode
            if (raw == null) parent.remove(input.field) else parent.set<com.fasterxml.jackson.databind.JsonNode>(input.field, mapper.readTree(raw))
            mvc.perform(request(input.method, input.url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest)
        }
        verifyNoInteractions(inventory, reservations, fulfillment, transfers, counts)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputs")
    fun `정수 입력은 Long 범위와 0 허용 규칙을 보존한다`(input: Input) {
        for (quantity in listOf(1L, Long.MAX_VALUE, 0L)) {
            val body = mapper.readTree(input.body) as ObjectNode
            (body.at(input.parent) as ObjectNode).put(input.field, quantity)
            mvc.perform(request(input.method, input.url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(if (quantity == 0L && !input.zeroAllowed) status().isBadRequest else status().isOk)
            val dto = mapper.treeToValue(body, input.type)
            assertEquals(quantity, mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(dto).at(input.parent).get(input.field).longValue())
        }
    }

    data class Input(val url: String, val method: HttpMethod, val body: String, val parent: String, val field: String,
        val type: Class<*>, val zeroAllowed: Boolean = false) {
        override fun toString() = "$url $field"
    }
    companion object {
        @JvmStatic fun inputs(): List<Input> {
            val id = UUID.randomUUID()
            val purchase = """{"supplierId":"$id","warehouseId":"$id","lines":[{"skuId":"$id","quantity":1,"unitCost":1}]}"""
            val inspection = """{"receiptLineId":"$id","goodQuantity":1,"damagedQuantity":0,"reason":"검수"}"""
            val returns = """{"lines":[{"lotId":"$id","good":1,"damaged":0}],"reason":"반품"}"""
            return listOf(
                Input("/inventory/purchase-orders/$id", HttpMethod.PUT, purchase, "/lines/0", "quantity", PurchaseOrderRequest::class.java),
                Input("/inventory/purchase-orders/$id", HttpMethod.PUT, purchase, "/lines/0", "unitCost", PurchaseOrderRequest::class.java),
                Input("/inventory/receipts/$id", HttpMethod.PUT, """{"purchaseOrderId":"$id","reason":"입고","lines":[{"purchaseLineId":"$id","lotCode":"L","expiresOn":"2030-01-01","quality":"GOOD","quantity":1}]}""", "/lines/0", "quantity", InboundRequest::class.java),
                Input("/inventory/inspections/$id", HttpMethod.PUT, inspection, "", "goodQuantity", InspectionRequest::class.java, true),
                Input("/inventory/inspections/$id", HttpMethod.PUT, inspection, "", "damagedQuantity", InspectionRequest::class.java, true),
                Input("/internal/reservations/$id", HttpMethod.PUT, """{"orderAttemptId":"$id","orderItemId":"$id","skuId":"$id","quantity":1,"expiresAt":"2030-01-01T00:00:00Z"}""", "", "quantity", ReservationRequest::class.java),
                Input("/inventory/outbounds/$id/pick", HttpMethod.POST, """{"reason":"피킹","lines":[{"lotId":"$id","quantity":1}]}""", "/lines/0", "quantity", OutboundActionRequest::class.java),
                Input("/inventory/returns/$id/inspection", HttpMethod.PUT, returns, "/lines/0", "good", ReturnInspectionRequest::class.java, true),
                Input("/inventory/returns/$id/inspection", HttpMethod.PUT, returns, "/lines/0", "damaged", ReturnInspectionRequest::class.java, true),
                Input("/inventory/transfers/$id", HttpMethod.PUT, """{"sourceWarehouseId":"$id","destinationWarehouseId":"$id","expectedArrivalAt":"2030-01-01T00:00:00Z","reason":"이동","lines":[{"lotId":"$id","quantity":1}]}""", "/lines/0", "quantity", TransferRequest::class.java),
                Input("/inventory/transfers/$id/receipts/$id", HttpMethod.PUT, """{"reason":"도착","lines":[{"lotId":"$id","quantity":1}]}""", "/lines/0", "quantity", TransferArrivalRequest::class.java),
                Input("/inventory/counts/$id/measurement", HttpMethod.PUT, """{"countedQuantity":1,"reason":"실사"}""", "", "countedQuantity", StockCountMeasurement::class.java, true)
            )
        }
    }
}
