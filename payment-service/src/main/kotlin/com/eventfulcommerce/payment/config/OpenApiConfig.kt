package com.eventfulcommerce.payment.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Eventful Commerce Payment API")
                    .description("결제 승인/실패 웹훅 수신 API 문서. 결제 예약, 환불 이력, 정산 이벤트는 내부 이벤트로 처리됩니다.")
                    .version("v1")
            )
}
