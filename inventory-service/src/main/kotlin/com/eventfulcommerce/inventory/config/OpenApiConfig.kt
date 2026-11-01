package com.eventfulcommerce.inventory.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean fun openApi(): OpenAPI = OpenAPI().info(Info().title("Eventful Commerce Inventory API").version("v1")
        .description("직매입 창고·공급사·발주·입고·로트·원장과 내부 예약. 관리자 JWT와 서버 간 토큰을 분리한다."))
        .components(Components().addSecuritySchemes("bearerAuth", SecurityScheme()
            .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
            .addSecuritySchemes("inventoryServiceToken", SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").description("order-service 전용 토큰")))
        .addSecurityItem(SecurityRequirement().addList("bearerAuth"))
}
