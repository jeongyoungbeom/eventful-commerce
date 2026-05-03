package com.eventfulcommerce.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication

@SpringBootApplication(
    scanBasePackages = ["com.eventfulcommerce"],
    exclude = [UserDetailsServiceAutoConfiguration::class]
)
class ApiGatewayApplication

fun main(args: Array<String>) {
    runApplication<ApiGatewayApplication>(*args)
}
