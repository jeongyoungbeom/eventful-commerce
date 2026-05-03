package com.eventfulcommerce.product.controller

import com.eventfulcommerce.common.auth.SecurityContextUtil
import com.eventfulcommerce.product.domain.ProductCategory
import com.eventfulcommerce.product.dto.CreateProductRequest
import com.eventfulcommerce.product.dto.ProductResponse
import com.eventfulcommerce.product.dto.UpdateProductRequest
import com.eventfulcommerce.product.dto.UpdateStockRequest
import com.eventfulcommerce.product.service.ProductService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/products")
class ProductController(private val productService: ProductService) {

    @PostMapping
    @PreAuthorize("hasRole('SELLER')")
    fun createProduct(@Valid @RequestBody request: CreateProductRequest): ResponseEntity<ProductResponse> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(request, sellerId))
    }

    @GetMapping
    fun getProducts(@RequestParam(required = false) category: ProductCategory?): ResponseEntity<List<ProductResponse>> =
        ResponseEntity.ok(productService.getProducts(category))

    @GetMapping("/{productId}")
    fun getProduct(@PathVariable productId: UUID): ResponseEntity<ProductResponse> =
        ResponseEntity.ok(productService.getProduct(productId))

    @GetMapping("/my")
    @PreAuthorize("hasRole('SELLER')")
    fun getMyProducts(): ResponseEntity<List<ProductResponse>> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(productService.getMyProducts(sellerId))
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('SELLER')")
    fun updateProduct(
        @PathVariable productId: UUID,
        @Valid @RequestBody request: UpdateProductRequest
    ): ResponseEntity<ProductResponse> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(productService.updateProduct(productId, request, sellerId))
    }

    @PatchMapping("/{productId}/stock")
    @PreAuthorize("hasRole('SELLER')")
    fun updateStock(
        @PathVariable productId: UUID,
        @Valid @RequestBody request: UpdateStockRequest
    ): ResponseEntity<ProductResponse> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        return ResponseEntity.ok(productService.updateStock(productId, request.delta, sellerId))
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasRole('SELLER')")
    fun deactivateProduct(@PathVariable productId: UUID): ResponseEntity<Void> {
        val sellerId = SecurityContextUtil.getCurrentUserId()
        productService.deactivateProduct(productId, sellerId)
        return ResponseEntity.noContent().build()
    }
}
