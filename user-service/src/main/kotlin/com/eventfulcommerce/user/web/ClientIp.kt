package com.eventfulcommerce.user.web

import jakarta.servlet.http.HttpServletRequest

// 외부 진입점 Nginx가 덮어쓴 단일 X-Real-IP만 신뢰한다. 서비스 직접 외부 노출은 허용하지 않는다.
fun clientIp(request: HttpServletRequest?): String =
    request?.getHeader("X-Real-IP")?.takeIf { it.isNotBlank() && ',' !in it }
        ?: request?.remoteAddr ?: "unknown"
