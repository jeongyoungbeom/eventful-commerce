package com.eventfulcommerce.user.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class CorsConfig {
    
    @Value("\${cors.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private lateinit var allowedOrigins: String
    
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        
        // 허용할 Origin 설정
        configuration.allowedOrigins = allowedOrigins.split(",").map { it.trim() }
        
        // 모든 HTTP 메서드 허용
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
        
        // 모든 헤더 허용
        configuration.allowedHeaders = listOf("*")
        
        // 인증 정보 포함 허용 (쿠키, Authorization 헤더 등)
        configuration.allowCredentials = true
        
        // preflight 요청 캐시 시간 (1시간)
        configuration.maxAge = 3600L
        
        // 노출할 헤더 설정
        configuration.exposedHeaders = listOf(
            "Authorization",
            "Content-Type",
            "X-Requested-With"
        )
        
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        
        return source
    }
    
    @Bean
    fun corsConfigurer(): WebMvcConfigurer {
        return object : WebMvcConfigurer {
            override fun addCorsMappings(registry: CorsRegistry) {
                registry.addMapping("/**")
                    .allowedOrigins(*allowedOrigins.split(",").map { it.trim() }.toTypedArray())
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                    .allowedHeaders("*")
                    .allowCredentials(true)
                    .maxAge(3600)
            }
        }
    }
}
