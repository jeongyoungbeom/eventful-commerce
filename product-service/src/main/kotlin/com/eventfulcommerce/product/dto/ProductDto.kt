package com.eventfulcommerce.product.dto

import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.domain.ProductStatus
import com.eventfulcommerce.product.domain.entity.Product
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID

data class CreateProductRequest(
    @field:NotBlank(message = "상품명은 필수입니다")
    val name: String,

    @field:NotBlank(message = "상품 설명은 필수입니다")
    val description: String,

    @field:Positive(message = "가격은 0보다 커야 합니다")
    val price: Long,

    @field:Min(value = 0, message = "재고는 0 이상이어야 합니다")
    val stock: Int,

    @field:NotNull(message = "카테고리는 필수입니다")
    val category: ProductCategory
)

data class UpdateProductRequest(
    @field:NotBlank(message = "상품명은 필수입니다")
    val name: String,

    @field:NotBlank(message = "상품 설명은 필수입니다")
    val description: String,

    @field:Positive(message = "가격은 0보다 커야 합니다")
    val price: Long,

    @field:NotNull(message = "카테고리는 필수입니다")
    val category: ProductCategory
)

data class UpdateStockRequest(
    @field:NotNull(message = "재고 변경량은 필수입니다")
    val delta: Int  // 양수: 재고 추가, 음수: 재고 감소
)

data class ProductResponse(
    val productId: UUID,
    val sellerId: UUID,
    val name: String,
    val description: String,
    val price: Long,
    val stock: Int,
    val category: ProductCategory,
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
            status = product.status,
            createdAt = product.createdAt,
            updatedAt = product.updatedAt
        )
    }
}
