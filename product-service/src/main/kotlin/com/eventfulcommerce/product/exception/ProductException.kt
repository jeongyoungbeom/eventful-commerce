package com.eventfulcommerce.product.exception

import java.util.UUID

class ProductNotFoundException(productId: UUID) :
    RuntimeException("상품을 찾을 수 없습니다: $productId")

class ProductOwnershipException(productId: UUID) :
    RuntimeException("해당 상품에 대한 권한이 없습니다: $productId")
