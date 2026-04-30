package com.eventfulcommerce.user.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class CorsConfig {
    
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        
        // 허용할 Origins
        configuration.allowedOrigins = listOf(
            "http://localhost:3000",      // Frontend (개발)
            "http://localhost:8080",      // API Gateway (개발)
            "https://yourdomain.com"      // 프로덕션 (나중에 변경)
        )
        
        // 허용할 HTTP 메서드
        configuration.allowedMethods = listOf(
            "GET",
            "POST",
            "PUT",
            "DELETE",
            "PATCH",
            "OPTIONS"
        )
        
        // 허용할 헤더
        configuration.allowedHeaders = listOf("*")
        
        // 인증 정보 포함 허용 (쿠키, Authorization 헤더 등)
        configuration.allowCredentials = true
        
        // Preflight 요청 캐시 시간 (초)
        configuration.maxAge = 3600L
        
        // 노출할 헤더 (클라이언트에서 접근 가능한 헤더)
        configuration.exposedHeaders = listOf(
            "Authorization",
            "Content-Type"
        )
        
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        
        return source
    }
}
