package com.eventfulcommerce.order.controller

import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.order.dto.OrderCancelResponse
import com.eventfulcommerce.order.dto.OrderResponse
import com.eventfulcommerce.order.dto.SellerOrderResponse
import com.eventfulcommerce.order.dto.SellerOrderCancelResponse
import com.eventfulcommerce.order.exception.OrderAccessDeniedException
import com.eventfulcommerce.order.request.OrdersRequest
import com.eventfulcommerce.order.service.OrderCancellationOutcome
import com.eventfulcommerce.order.service.OrdersService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
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
    private val ordersService: OrdersService,
    private val mixedOrdersService: com.eventfulcommerce.order.service.MixedOrdersService
) {

    @GetMapping("/orders")
    @Operation(summary = "내 주문 목록 조회", description = "로그인한 구매자의 주문 목록을 최신순으로 조회합니다. 각 주문은 판매자 주문과 주문 아이템으로 그룹화됩니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "주문 목록 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 실패")
    )
    fun getMyOrders(): ResponseEntity<List<OrderResponse>> {
        val userId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(ordersService.getMyOrders(userId).map { mixedOrdersService.decorate(it, userId) })
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

        return ResponseEntity.ok(ordersService.getOrdersByUserId(userId).map { mixedOrdersService.decorate(it, userId) })
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
        return ResponseEntity.ok(mixedOrdersService.getOrder(orderId, userId))
    }

    @PostMapping("/orders")
    @Operation(summary = "다상품 주문 생성", description = "입점 단독은 기존 예약·멱등 응답을 유지합니다. 직매입 포함 주문은 영속 시도 ID와 성공·실패·미확인 품목, 최종 금액·구성 버전·고객 확인 필요 여부를 반환합니다. 혼합 주문은 최종 구성 확인 후 결제를 생성합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "주문 생성 처리 완료. 일부 실패 상품이 있어도 HTTP 200으로 반환"),
        ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
        ApiResponse(responseCode = "409", description = "동일 Idempotency-Key 요청 처리 중 또는 다른 요청에 재사용됨"),
        ApiResponse(responseCode = "401", description = "인증 실패")
    )
    fun orders(
        @Parameter(
            name = "Idempotency-Key",
            `in` = ParameterIn.HEADER,
            required = true,
            description = "A client-generated key reused when retrying the same order request."
        )
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody ordersRequest: OrdersRequest
    ): ResponseEntity<OrderResponse> {
        val userId = SecurityContextUtil.getCurrentUserId()
        val key = requireNotNull(idempotencyKey?.takeIf { it.isNotBlank() }) {
            "Idempotency-Key header is required for order creation"
        }
        return ResponseEntity(mixedOrdersService.orders(ordersRequest, userId, key), HttpStatus.OK)
    }

    @PostMapping("/orders/{orderId}/confirmation")
    @Operation(summary = "혼합 주문의 최종 구성·금액 확인")
    fun confirm(@PathVariable orderId: UUID,
        @Valid @RequestBody request: com.eventfulcommerce.order.request.OrderConfirmationRequest): ResponseEntity<OrderResponse> =
        ResponseEntity.ok(mixedOrdersService.confirm(orderId, SecurityContextUtil.getCurrentUserId(), request))

    @PostMapping("/orders/{orderId}/cancel")
    @Operation(summary = "주문 전체 취소", description = "구매자가 주문 전체를 취소합니다. 결제 전이면 예약 재고를 해제하고, 결제 완료 후면 판매자 주문별 PaymentRefund 이력을 생성해 정산 차감 이벤트를 발행합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "주문 취소 성공"),
        ApiResponse(responseCode = "202", description = "배송 취소 확인 대기"),
        ApiResponse(responseCode = "409", description = "출고 후 취소 거절"),
        ApiResponse(responseCode = "503", description = "취소 잠금 획득 실패, 재시도 필요"),
        ApiResponse(responseCode = "403", description = "주문 소유자가 아님"),
        ApiResponse(responseCode = "404", description = "주문 없음")
    )
    fun cancelOrder(@PathVariable orderId: UUID): ResponseEntity<OrderCancelResponse> {
        val userId = SecurityContextUtil.getCurrentUserId()
        val outcome = mixedOrdersService.cancel(orderId, userId)
        return ResponseEntity.status(cancellationHttpStatus(outcome)).body(OrderCancelResponse(
            success = outcome in setOf(OrderCancellationOutcome.CANCELED, OrderCancellationOutcome.ALREADY_CANCELED),
            orderId = orderId, message = cancellationMessage(outcome), outcome = outcome
        ))
    }

    @PostMapping("/orders/{orderId}/seller-orders/{sellerOrderId}/cancel")
    @Operation(summary = "판매자 주문 부분 취소", description = "하나의 주문 안에서 특정 SellerOrder만 취소합니다. 다판매자 주문의 부분 취소/부분 환불을 처리하는 API입니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "판매자 주문 취소 성공"),
        ApiResponse(responseCode = "202", description = "배송 취소 확인 대기"),
        ApiResponse(responseCode = "409", description = "출고 후 취소 거절"),
        ApiResponse(responseCode = "503", description = "취소 잠금 획득 실패, 재시도 필요"),
        ApiResponse(responseCode = "403", description = "주문 소유자가 아님"),
        ApiResponse(responseCode = "404", description = "주문 또는 판매자 주문 없음")
    )
    fun cancelSellerOrder(
        @PathVariable orderId: UUID,
        @PathVariable sellerOrderId: UUID
    ): ResponseEntity<SellerOrderCancelResponse> {
        val userId = SecurityContextUtil.getCurrentUserId()
        val outcome = mixedOrdersService.cancelSeller(orderId, sellerOrderId, userId)
        return ResponseEntity.status(cancellationHttpStatus(outcome)).body(SellerOrderCancelResponse(
            success = outcome in setOf(OrderCancellationOutcome.CANCELED, OrderCancellationOutcome.ALREADY_CANCELED),
            orderId = orderId, sellerOrderId = sellerOrderId,
            message = cancellationMessage(outcome), outcome = outcome
        ))
    }

    @PostMapping("/orders/{orderId}/items/{orderItemId}/cancel")
    @Operation(summary = "직매입 품목 전량 취소")
    fun cancelItem(@PathVariable orderId: UUID, @PathVariable orderItemId: UUID): ResponseEntity<OrderCancelResponse> {
        val outcome = mixedOrdersService.cancelItem(orderId, orderItemId, SecurityContextUtil.getCurrentUserId())
        return ResponseEntity.status(cancellationHttpStatus(outcome)).body(OrderCancelResponse(
            outcome in setOf(OrderCancellationOutcome.CANCELED, OrderCancellationOutcome.ALREADY_CANCELED),
            orderId, cancellationMessage(outcome), outcome))
    }

    private fun cancellationHttpStatus(outcome: OrderCancellationOutcome): HttpStatus = when (outcome) {
        OrderCancellationOutcome.PENDING -> HttpStatus.ACCEPTED
        OrderCancellationOutcome.REJECTED -> HttpStatus.CONFLICT
        OrderCancellationOutcome.LOCK_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
        else -> HttpStatus.OK
    }

    private fun cancellationMessage(outcome: OrderCancellationOutcome): String = when (outcome) {
        OrderCancellationOutcome.PENDING -> "취소 처리 중입니다. 주문 조회에서 결과를 확인하세요."
        OrderCancellationOutcome.REJECTED -> "출고된 배송은 취소할 수 없습니다. 판매자별 결과를 확인하세요."
        OrderCancellationOutcome.LOCK_UNAVAILABLE -> "취소 처리 중입니다. 잠시 후 다시 시도하세요."
        else -> "주문이 취소되었습니다"
    }

    @GetMapping("/seller-orders")
    @Operation(summary = "판매자 주문 목록 조회", description = "로그인한 판매자의 SellerOrder 목록을 최신순으로 조회합니다. 다판매자 주문에서는 판매자별 주문 처리/정산/배송의 기준이 됩니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "판매자 주문 목록 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 실패")
    )
    fun getMySellerOrders(): ResponseEntity<List<SellerOrderResponse>> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(ordersService.getSellerOrders(sellerId))
    }
}
