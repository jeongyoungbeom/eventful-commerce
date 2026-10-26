package com.eventfulcommerce.payment.webhook

import com.eventfulcommerce.payment.domain.PaymentWebhookRequest
import org.springframework.http.HttpHeaders

/**
 * PG별 실제 인증 규격을 구현하는 경계다. 원문과 headers로 발신자를 인증하고,
 * orderId/amount/pgTxId/result 전체가 검증된 경우에만 정규화된 결과를 반환해야 한다.
 * 서명 검증 또는 PG 조회에 필요한 재전송/재생 방어도 해당 PG 계약에 따라 수행한다.
 * 인증 누락·실패는 ResponseStatusException(UNAUTHORIZED), 검증 불가는
 * ResponseStatusException(SERVICE_UNAVAILABLE)로 거부한다. 검증 실패 시 반환하지 않는다.
 * 사용자 JWT나 로컬 시뮬레이션 토큰을 실제 PG 인증으로 인정하지 않는다.
 */
fun interface PaymentWebhookVerifier {
    fun verify(rawBody: ByteArray, headers: HttpHeaders): PaymentWebhookRequest
}
