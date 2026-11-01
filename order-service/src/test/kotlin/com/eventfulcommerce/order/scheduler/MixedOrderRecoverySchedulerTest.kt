package com.eventfulcommerce.order.scheduler

import com.eventfulcommerce.order.config.MixedOrderSchedulingConfig
import com.eventfulcommerce.order.repository.MixedOrderAttemptRepository
import com.eventfulcommerce.order.service.MixedOrdersService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.context.annotation.*
import org.springframework.core.env.MapPropertySource
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MixedOrderRecoverySchedulerTest {
    @Test
    fun `혼합 복구 대기 중에도 기본 스케줄러는 진행하고 복구 배치는 겹치지 않는다`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("test", mapOf(
                "order.direct-inventory.recovery-delay-ms" to "10")))
            context.register(ProbeConfiguration::class.java)
            context.refresh()
            val probe = context.getBean(Probe::class.java)
            try {
                assertTrue(probe.recoveryStarted.await(3, TimeUnit.SECONDS))
                assertTrue(probe.backgroundRan.await(3, TimeUnit.SECONDS), "기존 정기 작업이 복구에 막혔다")
                assertEquals(1, probe.recoveryCalls.get(), "동일 복구 배치를 중첩 실행했다")
                assertEquals(1, context.getBean("taskScheduler", ThreadPoolTaskScheduler::class.java).poolSize)
                assertEquals(1, context.getBean("mixedOrderTaskScheduler", ThreadPoolTaskScheduler::class.java).poolSize)
                assertTrue(probe.recoveryThread.startsWith("mixed-order-recovery-"))
                assertNotEquals(probe.recoveryThread, probe.backgroundThread)
                verify(exactly = 1) {
                    context.getBean(MixedOrderAttemptRepository::class.java).findRecoverable(any(), PageRequest.of(0, 20))
                }
            } finally { probe.recoveryMayFinish.countDown() }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(TaskSchedulingAutoConfiguration::class, MixedOrderSchedulingConfig::class, MixedOrderRecoveryScheduler::class)
    class ProbeConfiguration {
        @Bean fun probe() = Probe()
        @Bean fun attempts(): MixedOrderAttemptRepository = mockk<MixedOrderAttemptRepository>().also {
            every { it.findRecoverable(any(), any()) } returns listOf(UUID.randomUUID())
        }
        @Bean fun service(probe: Probe): MixedOrdersService = mockk<MixedOrdersService>().also {
            every { it.recover(any(), null) } answers {
                probe.recoveryThread = Thread.currentThread().name
                probe.recoveryCalls.incrementAndGet()
                probe.recoveryStarted.countDown()
                check(probe.recoveryMayFinish.await(10, TimeUnit.SECONDS))
                mockk()
            }
        }
    }

    class Probe {
        val recoveryStarted = CountDownLatch(1)
        val recoveryMayFinish = CountDownLatch(1)
        val backgroundRan = CountDownLatch(10)
        val recoveryCalls = AtomicInteger()
        @Volatile var recoveryThread = ""
        @Volatile var backgroundThread = ""

        @Scheduled(fixedDelay = 10)
        fun tick() {
            if (recoveryStarted.count == 0L && recoveryMayFinish.count != 0L) {
                backgroundThread = Thread.currentThread().name
                backgroundRan.countDown()
            }
        }
    }
}
