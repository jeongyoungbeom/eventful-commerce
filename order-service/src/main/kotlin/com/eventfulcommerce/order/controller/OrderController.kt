package com.eventfulcommerce.order.controller

import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.order.domain.OrdersRequest
import com.eventfulcommerce.order.dto.OrderResponse
import com.eventfulcommerce.order.dto.SellerOrderResponse
import com.eventfulcommerce.order.exception.OrderAccessDeniedException
import com.eventfulcommerce.order.service.OrdersService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Validated
@RestController
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Orders", description = "다판매자 다상품 주문 생성, 주문 조회, 주문 취소 API")
class OrderController(
    private val ordersService: OrdersService
) {

    @GetMapping("/orders")
    @Operation(summary = "내 주문 목록 조회", description = "로그인한 구매자의 주문 목록을 최신순으로 조회합니다. 각 주문은 판매자 주문과 주문 아이템으로 그룹화됩니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "주문 목록 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 실패")
    )
    fun getMyOrders(): ResponseEntity<List<OrderResponse>> {
        val userId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(ordersService.getMyOrders(userId).map { OrderResponse.from(it) })
    }

    @GetMapping("/orders/users/{userId}")
    @Operation(summary = "관리자용 사용자 주문 조회", description = "ADMIN 권한으로 특정 사용자의 주문 목록을 조회합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "사용자 주문 목록 조회 성공"),
        ApiResponse(responseCode = "403", description = "ADMIN 권한 필요")
    )
    fun getOrdersByUser(@PathVariable userId: UUID): ResponseEntity<List<OrderResponse>> {
        val role = SecurityContextUtil.getCurrentUserRole()
        if (role != "ADMIN") {
            throw OrderAccessDeniedException("특정 사용자 주문 조회는 관리자만 가능합니다.")
        }

        return ResponseEntity.ok(ordersService.getOrdersByUserId(userId).map { OrderResponse.from(it) })
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "주문 상세 조회", description = "주문 ID로 주문 상세를 조회합니다. 판매자별 SellerOrder와 상품별 OrderItem, 재고 예약 ID, 정산 예정 금액을 포함합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "주문 상세 조회 성공"),
        ApiResponse(responseCode = "403", description = "주문 소유자가 아님"),
        ApiResponse(responseCode = "404", description = "주문 없음")
    )
    fun getOrder(@PathVariable orderId: UUID): ResponseEntity<OrderResponse> {
        val userId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(OrderResponse.from(ordersService.getOrder(orderId, userId)))
    }

    @PostMapping("/orders")
    @Operation(summary = "다상품 주문 생성", description = "items 배열로 여러 상품을 한 번에 주문합니다. 서로 다른 판매자의 상품은 SellerOrder로 분리됩니다. 재고 부족 상품은 주문에서 제외되고 failedItems에 productId, reason, requestedQuantity, availableQuantity로 반환됩니다. 모든 상품이 실패하면 주문을 저장하지 않고 orderId는 null입니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "주문 생성 처리 완료. 일부 실패 상품이 있어도 HTTP 200으로 반환"),
        ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
        ApiResponse(responseCode = "401", description = "인증 실패")
    )
    fun orders(@Valid @RequestBody ordersRequest: OrdersRequest): ResponseEntity<OrderResponse> {
        val userId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity(ordersService.orders(ordersRequest, userId), HttpStatus.OK)
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Operation(summary = "주문 전체 취소", description = "구매자가 주문 전체를 취소합니다. 결제 전이면 예약 재고를 해제하고, 결제 완료 후면 판매자 주문별 PaymentRefund 이력을 생성해 정산 차감 이벤트를 발행합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "주문 취소 성공"),
        ApiResponse(responseCode = "400", description = "이미 처리되었거나 취소 불가능한 상태"),
        ApiResponse(responseCode = "403", description = "주문 소유자가 아님"),
        ApiResponse(responseCode = "404", description = "주문 없음")
    )
    fun cancelOrder(@PathVariable orderId: UUID): ResponseEntity<Map<String, Any>> {
        val userId = SecurityContextUtil.getCurrentUserId()
        val success = ordersService.cancelOrder(orderId, userId)

        return if (success) {
            ResponseEntity.ok(mapOf(
                "success" to true,
                "orderId" to orderId,
                "message" to "주문이 취소되었습니다"
            ))
        } else {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "orderId" to orderId,
                "message" to "주문 취소 실패 (이미 처리되었거나 취소 불가능한 상태)"
            ))
        }
    }

    @PostMapping("/orders/{orderId}/seller-orders/{sellerOrderId}/cancel")
    @Operation(summary = "판매자 주문 부분 취소", description = "하나의 주문 안에서 특정 SellerOrder만 취소합니다. 다판매자 주문의 부분 취소/부분 환불을 처리하는 API입니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "판매자 주문 취소 성공"),
        ApiResponse(responseCode = "400", description = "이미 처리되었거나 취소 불가능한 상태"),
        ApiResponse(responseCode = "403", description = "주문 소유자가 아님"),
        ApiResponse(responseCode = "404", description = "주문 또는 판매자 주문 없음")
    )
    fun cancelSellerOrder(
        @PathVariable orderId: UUID,
        @PathVariable sellerOrderId: UUID
    ): ResponseEntity<Map<String, Any>> {
        val userId = SecurityContextUtil.getCurrentUserId()
        val success = ordersService.cancelSellerOrder(orderId, sellerOrderId, userId)

        return if (success) {
            ResponseEntity.ok(mapOf(
                "success" to true,
                "orderId" to orderId,
                "sellerOrderId" to sellerOrderId,
                "message" to "판매자 주문이 취소되었습니다"
            ))
        } else {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "orderId" to orderId,
                "sellerOrderId" to sellerOrderId,
                "message" to "판매자 주문 취소 실패 (이미 처리되었거나 취소 불가능한 상태)"
            ))
        }
    }

    @GetMapping("/seller-orders")
    @Operation(summary = "판매자 주문 목록 조회", description = "로그인한 판매자의 SellerOrder 목록을 최신순으로 조회합니다. 다판매자 주문에서는 판매자별 주문 처리/정산/배송의 기준이 됩니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "판매자 주문 목록 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 실패")
    )
    fun getMySellerOrders(): ResponseEntity<List<SellerOrderResponse>> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(ordersService.getSellerOrders(sellerId).map { SellerOrderResponse.from(it) })
    }
}
