package com.eventfulcommerce.settlement.config

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "spring.data.redis.cluster")
data class RedisClusterProperties(
    var nodes: List<String> = emptyList()
)

@Configuration
@EnableConfigurationProperties(RedisClusterProperties::class)
class RedissonConfig(
    private val redisClusterProperties: RedisClusterProperties
) {

    @Bean
    fun redissonClient(): RedissonClient {
        val config = Config()
        val clusterServersConfig = config.useClusterServers()

        redisClusterProperties.nodes.forEach { node ->
            clusterServersConfig.addNodeAddress("redis://$node")
        }

        clusterServersConfig
            .setScanInterval(2000)
            .setConnectTimeout(10000)
            .setTimeout(3000)
            .setRetryAttempts(3)
            .setRetryInterval(1500)
            .setCheckSlotsCoverage(false)

        return Redisson.create(config)
    }
}
