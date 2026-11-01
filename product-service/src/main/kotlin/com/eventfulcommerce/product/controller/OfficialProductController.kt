package com.eventfulcommerce.product.controller

import com.eventfulcommerce.product.domain.entity.OfficialSeller
import com.eventfulcommerce.product.dto.CreateProductRequest
import com.eventfulcommerce.product.dto.ProductResponse
import com.eventfulcommerce.product.dto.UpdateProductRequest
import com.eventfulcommerce.product.service.ProductService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/products/official")
@Tag(name = "Official Products", description = "공식 판매 주체와 직매입 상품 관리. S1에서는 직매입 구매가 비활성화됩니다.")
class OfficialProductController(private val productService: ProductService) {
    @PutMapping("/seller")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "공식 판매 주체 발급·표시 이름 변경")
    fun provision(@Valid @RequestBody request: OfficialSellerRequest): OfficialSeller =
        productService.provisionOfficialSeller(request.displayName)

    @GetMapping("/seller")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "공식 판매 주체 조회")
    fun seller(): OfficialSeller = productService.getOfficialSeller()

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "직매입 상품 등록", description = "초기 stock은 0만 허용합니다. 상품 ID가 SKU ID입니다. 판매·입고는 후속 재고 서비스에서 활성화합니다.")
    fun create(@RequestPart("request") @Valid request: CreateProductRequest,
        @RequestPart("images", required = false) images: List<MultipartFile>?): ProductResponse =
        productService.createOfficialProduct(request, images)

    @PutMapping("/{productId}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "직매입 상품 정보 수정")
    fun update(@PathVariable productId: UUID, @RequestPart("request") @Valid request: UpdateProductRequest,
        @RequestPart("images", required = false) images: List<MultipartFile>?): ProductResponse =
        productService.updateOfficialProduct(productId, request, images)

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "직매입 상품 비활성화")
    fun deactivate(@PathVariable productId: UUID) { productService.deactivateOfficialProduct(productId) }
}

data class OfficialSellerRequest(@field:NotBlank @field:Size(max = 100) val displayName: String)
