package com.eventfulcommerce.product.dto

import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductLabel
import com.eventfulcommerce.product.domain.ProductStatus
import com.eventfulcommerce.product.domain.entity.Product
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID

@Schema(description = "상품 생성 요청")
data class CreateProductRequest(
    @field:Schema(description = "상품명", example = "봄 튤립 꽃다발", required = true)
    @field:NotBlank(message = "상품명은 필수입니다")
    val name: String,

    @field:Schema(description = "상품 상세 설명", example = "선물용으로 좋은 튤립 꽃다발입니다.", required = true)
    @field:NotBlank(message = "상품 설명은 필수입니다")
    val description: String,

    @field:Schema(description = "판매 가격", example = "29000", minimum = "1", required = true)
    @field:Positive(message = "가격은 0보다 커야 합니다")
    val price: Long,

    @field:Schema(description = "초기 재고 수량", example = "50", minimum = "0", required = true)
    @field:Min(value = 0, message = "재고는 0 이상이어야 합니다")
    val stock: Int,

    @field:Schema(description = "상품 카테고리", example = "FLOWERS", required = true)
    @field:NotNull(message = "카테고리는 필수입니다")
    val category: ProductCategory,

    @field:Schema(description = "상품 라벨 목록", example = "[\"BEST\", \"NEW\"]")
    val labels: Set<ProductLabel> = emptySet()
)

@Schema(description = "상품 수정 요청")
data class UpdateProductRequest(
    @field:NotBlank(message = "상품명은 필수입니다")
    val name: String,

    @field:NotBlank(message = "상품 설명은 필수입니다")
    val description: String,

    @field:Positive(message = "가격은 0보다 커야 합니다")
    val price: Long,

    @field:NotNull(message = "카테고리는 필수입니다")
    val category: ProductCategory,

    val labels: Set<ProductLabel> = emptySet()
)

@Schema(description = "상품 재고 증감 요청")
data class UpdateStockRequest(
    @field:Schema(description = "재고 증감량. 양수는 추가, 음수는 차감입니다.", example = "10")
    val delta: Int  // 양수: 재고 추가, 음수: 재고 감소
)

@Schema(description = "상품 응답")
data class ProductResponse(
    val productId: UUID,
    val sellerId: UUID,
    val name: String,
    val description: String,
    val price: Long,
    val stock: Int,
    val category: ProductCategory,
    val labels: Set<ProductLabel>,
    val imageUrls: List<String>,
    val status: ProductStatus,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(product: Product) = ProductResponse(
            productId = product.id,
            sellerId = product.sellerId,
            name = product.name,
            description = product.description,
            price = product.price,
            stock = product.stock,
            category = product.category,
            labels = product.labels.toSet(),
            imageUrls = product.imageUrls.toList(),
            status = product.status,
            createdAt = product.createdAt,
            updatedAt = product.updatedAt
        )
    }
}
