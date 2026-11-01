package com.eventfulcommerce.inventory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock
import java.time.ZoneId

@SpringBootApplication(scanBasePackages = ["com.eventfulcommerce"])
@EntityScan("com.eventfulcommerce")
@EnableJpaRepositories("com.eventfulcommerce")
@EnableScheduling
class InventoryServiceApplication {
    @Bean fun inventoryClock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}

fun main(args: Array<String>) { runApplication<InventoryServiceApplication>(*args) }
