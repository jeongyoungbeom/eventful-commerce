package com.eventfulcommerce.common.auth

import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl

object RoleHierarchyProvider {

    fun create(): RoleHierarchy {
        val hierarchy = RoleHierarchyImpl()
        hierarchy.setHierarchy(
            """
            ROLE_ADMIN > ROLE_SELLER
            ROLE_ADMIN > ROLE_USER
            """.trimIndent()
        )
        return hierarchy
    }
}
