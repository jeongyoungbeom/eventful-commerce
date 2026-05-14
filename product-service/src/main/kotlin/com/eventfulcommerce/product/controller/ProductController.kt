package com.eventfulcommerce.product.controller

import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductLabel
import com.eventfulcommerce.product.dto.CreateProductRequest
import com.eventfulcommerce.product.dto.ProductResponse
import com.eventfulcommerce.product.dto.UpdateProductRequest
import com.eventfulcommerce.product.dto.UpdateStockRequest
import com.eventfulcommerce.product.service.ProductService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/products")
@Tag(name = "Products", description = "상품 등록, 조회, 수정, 재고 변경, 비활성화 API")
class ProductController(private val productService: ProductService) {

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("hasRole('SELLER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "상품 등록", description = "SELLER 권한으로 상품 기본 정보와 선택 이미지 목록을 multipart/form-data로 등록합니다. 등록 후 PRODUCT_REGISTERED 이벤트를 통해 주문 서비스 읽기 모델과 Redis 재고가 초기화됩니다.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "상품 등록 성공"),
        ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
        ApiResponse(responseCode = "401", description = "인증 실패"),
        ApiResponse(responseCode = "403", description = "SELLER 권한 필요")
    )
    fun createProduct(
        @Parameter(description = "상품 생성 JSON 파트", required = true)
        @RequestPart("request") @Valid request: CreateProductRequest,
        @Parameter(description = "상품 이미지 파일 목록. 생략 가능")
        @RequestPart("images", required = false) images: List<MultipartFile>?
    ): ResponseEntity<ProductResponse> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request, sellerId, images))
    }

    @GetMapping
    @Operation(summary = "상품 목록 조회", description = "판매 중인 상품 목록을 조회합니다. category와 label을 함께 또는 각각 사용해 필터링할 수 있습니다.")
    @ApiResponses(ApiResponse(responseCode = "200", description = "상품 목록 조회 성공"))
    fun getProducts(
        @RequestParam(required = false) category: ProductCategory?,
        @RequestParam(required = false) label: ProductLabel?
    ): ResponseEntity<List<ProductResponse>> =
        ResponseEntity.ok(productService.getProducts(category, label))

    @GetMapping("/{productId}")
    @Operation(summary = "상품 상세 조회", description = "상품 ID로 단일 상품의 가격, 재고, 이미지, 상태 정보를 조회합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "상품 상세 조회 성공"),
        ApiResponse(responseCode = "404", description = "상품 없음")
    )
    fun getProduct(@PathVariable productId: UUID): ResponseEntity<ProductResponse> =
        ResponseEntity.ok(productService.getProduct(productId))

    @GetMapping("/my")
    @PreAuthorize("hasRole('SELLER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "내 상품 목록 조회", description = "로그인한 판매자가 직접 등록한 상품 목록을 조회합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "내 상품 목록 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 실패"),
        ApiResponse(responseCode = "403", description = "SELLER 권한 필요")
    )
    fun getMyProducts(): ResponseEntity<List<ProductResponse>> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(productService.getMyProducts(sellerId))
    }

    @PutMapping("/{productId}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("hasRole('SELLER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "상품 정보 수정", description = "상품 소유 판매자가 상품명, 설명, 가격, 카테고리, 라벨, 이미지 목록을 수정합니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "상품 수정 성공"),
        ApiResponse(responseCode = "400", description = "요청 값 검증 실패"),
        ApiResponse(responseCode = "403", description = "상품 소유자가 아님"),
        ApiResponse(responseCode = "404", description = "상품 없음")
    )
    fun updateProduct(
        @PathVariable productId: UUID,
        @RequestPart("request") @Valid request: UpdateProductRequest,
        @RequestPart("images", required = false) images: List<MultipartFile>?
    ): ResponseEntity<ProductResponse> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(productService.updateProduct(productId, request, sellerId, images))
    }

    @PatchMapping("/{productId}/stock")
    @PreAuthorize("hasRole('SELLER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "상품 재고 증감", description = "상품 소유 판매자가 delta 값만큼 재고를 증감합니다. 양수는 입고, 음수는 차감이며 PRODUCT_STOCK_UPDATED 이벤트로 주문 서비스 Redis 재고에도 반영됩니다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재고 변경 성공"),
        ApiResponse(responseCode = "400", description = "재고가 음수가 되는 요청"),
        ApiResponse(responseCode = "403", description = "상품 소유자가 아님"),
        ApiResponse(responseCode = "404", description = "상품 없음")
    )
    fun updateStock(
        @PathVariable productId: UUID,
        @Valid @RequestBody request: UpdateStockRequest
    ): ResponseEntity<ProductResponse> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(productService.updateStock(productId, request.delta, sellerId))
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasRole('SELLER')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "상품 비활성화", description = "상품 소유 판매자가 상품을 비활성화합니다. 비활성화된 상품은 신규 주문 대상에서 제외됩니다.")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "상품 비활성화 성공"),
        ApiResponse(responseCode = "403", description = "상품 소유자가 아님"),
        ApiResponse(responseCode = "404", description = "상품 없음")
    )
    fun deactivateProduct(@PathVariable productId: UUID): ResponseEntity<Void> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        productService.deactivateProduct(productId, sellerId)
        return ResponseEntity.noContent().build()
    }
}
