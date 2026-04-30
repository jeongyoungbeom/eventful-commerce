package com.eventfulcommerce.user.config

import com.eventfulcommerce.user.interceptor.RateLimitInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val rateLimitInterceptor: RateLimitInterceptor
) : WebMvcConfigurer {
    
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/**")  // 모든 경로에 적용
            .excludePathPatterns(
                "/actuator/**",  // Actuator 제외
                "/error"         // Error 페이지 제외
            )
    }
}
