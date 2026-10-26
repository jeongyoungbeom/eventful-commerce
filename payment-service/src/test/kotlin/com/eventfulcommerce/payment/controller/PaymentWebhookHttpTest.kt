package com.eventfulcommerce.payment.controller

import com.eventfulcommerce.payment.domain.PaymentWebhookRequest
import com.eventfulcommerce.payment.exception.GlobalExceptionHandler
import com.eventfulcommerce.payment.service.PaymentWebhookService
import com.eventfulcommerce.payment.webhook.PaymentWebhookVerifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.*
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

class PaymentWebhookHttpTest {
    private val runner = WebApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withUserConfiguration(PaymentWebhookHttpConfiguration::class.java)
    private val orderId = UUID.randomUUID()
    private val body = """{ "orderId":"$orderId", "result":"SUCCESS", "pgTxId":"PG-http", "amount":20000 }"""
    private val token = "local-only-test-token-0123456789abcdef"

    @Test
    fun `실제 수신은 검증자 미구성 시 인증 헤더 유무와 무관하게 503으로 차단한다`() {
        runner.run { context ->
            val mvc = mvc(context.sourceApplicationContext)
            listOf("", token).forEach { credential ->
                mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Payment-Simulation-Token", credential).content(body))
                    .andExpect(status().isServiceUnavailable)
            }
            verifyNoInteractions(context.getBean(PaymentWebhookService::class.java))
        }
    }

    @Test
    fun `PG 계약 검증자는 정확한 원문 headers를 받고 검증된 값만 서비스에 전달한다`() {
        val verified = PaymentWebhookRequest(orderId, "SUCCESS", "PG-http", 20_000)
        val verifier = PaymentWebhookVerifier { raw, headers ->
            assertArrayEquals(body.toByteArray(), raw)
            assertEquals("provider-test-credential", headers.getFirst("X-Provider-Test"))
            verified
        }
        runner.withBean(PaymentWebhookVerifier::class.java, { verifier }).run { context ->
            mvc(context.sourceApplicationContext).perform(post("/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON).header("X-Provider-Test", "provider-test-credential").content(body))
                .andExpect(status().isOk)
            verify(context.getBean(PaymentWebhookService::class.java)).handle(verified)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "wrong"])
    fun `실제 수신의 인증 누락 실패는 검증자가 거부하고 서비스를 호출하지 않는다`(credential: String) {
        val verifier = PaymentWebhookVerifier { _, headers ->
            assertEquals(credential, headers.getFirst("X-Provider-Test"))
            // PG 서명 구현이 아닌 계약 테스트용 검증자다.
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "테스트 PG 인증 거부")
        }
        runner.withBean(PaymentWebhookVerifier::class.java, { verifier }).run { context ->
            mvc(context.sourceApplicationContext).perform(post("/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON).header("X-Provider-Test", credential).content(body))
                .andExpect(status().isUnauthorized)
            verifyNoInteractions(context.getBean(PaymentWebhookService::class.java))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["dev", "docker", "prod", "prod,payment-simulation"])
    fun `일반 프로필과 prod는 enabled 강제 설정에도 시뮬레이션을 만들지 않는다`(profiles: String) {
        runner.withPropertyValues("spring.profiles.active=$profiles", "payment.simulation.enabled=true",
            "payment.simulation.token=$token").run { context ->
            assertTrue(context.getBeansOfType(PaymentSimulationController::class.java).isEmpty())
            mvc(context.sourceApplicationContext).perform(post("/payments/simulation/webhook")
                .contentType(MediaType.APPLICATION_JSON).header("X-Payment-Simulation-Token", token).content(body))
                .andExpect(status().isNotFound)
            verifyNoInteractions(context.getBean(PaymentWebhookService::class.java))
        }
    }

    @Test
    fun `기본 프로필과 명시적 disabled 시뮬레이션은 비활성이다`() {
        runner.run { context ->
            assertTrue(context.getBeansOfType(PaymentSimulationController::class.java).isEmpty())
            assertEquals("false", context.environment.getProperty("payment.simulation.enabled"))
        }
        runner.withPropertyValues("spring.profiles.active=dev,payment-simulation", "payment.simulation.enabled=false")
            .run { context -> assertTrue(context.getBeansOfType(PaymentSimulationController::class.java).isEmpty()) }
    }

    @Test
    fun `명시적 로컬 시뮬레이션도 토큰이 미구성이면 503이다`() {
        runner.withPropertyValues("spring.profiles.active=dev,payment-simulation", "payment.simulation.token=")
            .run { context ->
                mvc(context.sourceApplicationContext).perform(post("/payments/simulation/webhook")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isServiceUnavailable)
                verifyNoInteractions(context.getBean(PaymentWebhookService::class.java))
            }
    }

    @Test
    fun `로컬 시뮬레이션은 전용 토큰만 허용하고 같은 토큰으로 실제 PG 경로를 열지 않는다`() {
        runner.withPropertyValues("spring.profiles.active=dev,payment-simulation", "payment.simulation.token=$token")
            .run { context ->
                val mvc = mvc(context.sourceApplicationContext)
                mvc.perform(post("/payments/simulation/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized)
                mvc.perform(post("/payments/simulation/webhook").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Payment-Simulation-Token", "wrong").content(body)).andExpect(status().isUnauthorized)
                verifyNoInteractions(context.getBean(PaymentWebhookService::class.java))
                mvc.perform(post("/payments/simulation/webhook").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Payment-Simulation-Token", token).content(body)).andExpect(status().isOk)
                verify(context.getBean(PaymentWebhookService::class.java))
                    .handle(PaymentWebhookRequest(orderId, "SUCCESS", "PG-http", 20_000))
                mvc.perform(post("/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Payment-Simulation-Token", token).content(body)).andExpect(status().isServiceUnavailable)
            }
    }

    private fun mvc(context: WebApplicationContext): MockMvc = MockMvcBuilders.webAppContextSetup(context).build()
}

@Configuration(proxyBeanMethods = false)
@EnableWebMvc
@Import(PaymentWebhookController::class, PaymentSimulationController::class, GlobalExceptionHandler::class)
class PaymentWebhookHttpConfiguration {
    @Bean fun paymentWebhookService(): PaymentWebhookService = mock()
}
