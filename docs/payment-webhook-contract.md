# 결제 웹훅 검증 계약

## 실제 PG 수신

`POST /payments/webhook` (Gateway: `/api/payments/webhook`)은 JSON 원문 bytes와 HTTP headers를 `PaymentWebhookVerifier`에 전달한다. 검증자가 없으면 **503**이며 사용자 JWT·로컬 토큰으로 우회할 수 없다. 현재 저장소에는 특정 PG의 인증 규격이나 실제 검증자 구현이 없다.

실제 연동 시 해당 PG의 공식 계약에 맞는 검증자 bean을 추가해야 한다. 발신자와 orderId·amount·pgTxId·result 전체를 인증·검증한 뒤 `PaymentWebhookRequest`를 반환한다. PG 계약에 따른 재생·재전송 방어도 검증자 책임이다. 인증 누락·실패는 `ResponseStatusException(UNAUTHORIZED)`, 검증 불가는 `ResponseStatusException(SERVICE_UNAVAILABLE)`로 거부한다. 임의 HMAC이나 사용자 JWT를 PG 인증으로 대체하지 않는다.

서비스의 공통 검증은 모든 수신 경로에 적용한다.

- `result`: 정확히 `SUCCESS` 또는 `FAILED`.
- `amount`: 필수 양수이며 저장된 결제 원금과 같아야 한다. 부분 환불 후에도 원래 결제 금액으로 검증한다.
- `pgTxId`: 필수, 1~200자, 공백·제어문자 제외. 최초 종결 때 저장하며 전역 UNIQUE로 다른 결제의 재사용을 막는다. 여러 PG/가맹점의 ID namespace는 실제 연동 시 검증자가 충돌 없이 정규화해야 한다.
- 기존 거래 ID·금액과 일치하는 종결 요청은 효과를 반복하지 않는다. 같은 거래에서 늦게 온 상반된 허용 결과도 S2의 최초 종결 결과 유지 정책을 따른다. 다른 거래 ID 또는 잘못된 내용은 종결 뒤에도 거부한다.
- 검증 오류는 400, 인증 실패는 401, DB UNIQUE 경합은 409다. 결제 상태·거래 ID·환불·Outbox는 같은 트랜잭션이며 실패 시 함께 롤백한다.

Gateway는 **정확한 POST `/api/payments/webhook`만** 사용자 JWT 없이 전달한다. PG 인증은 payment-service가 책임지므로 내부 포트로 직접 접근해도 동일하게 차단한다.

## 로컬 시뮬레이션

`POST /payments/simulation/webhook`은 실제 PG 수신과 분리한다. 다음 조건을 모두 만족해야 한다.

1. payment-service의 `payment-simulation` 프로필을 명시한다. 예: `dev,payment-simulation` 또는 `docker,payment-simulation`.
2. `payment.simulation.enabled=true`여야 한다. 해당 프로필의 설정 파일에 true가 있으며 명시적 false로 다시 끌 수 있다.
3. `PAYMENT_SIMULATION_TOKEN`을 설정하고 동일 값을 `X-Payment-Simulation-Token` 헤더로 전달한다. 미구성은 503, 누락·불일치는 401이다. 비교는 일정 시간 비교 API를 사용한다.
4. Gateway 경유 시 정상 사용자 access JWT가 추가로 필요하다. 전용 토큰만으로 Gateway를 통과할 수 없다.

기본 dev/docker에서는 경로가 없으며, prod는 시뮬레이션 프로필·enabled를 함께 지정해도 경로가 생기지 않는다. 로컬 시뮬레이션은 실제 PG 인증을 제공하지 않는다.

로컬 Docker 설정은 `docker-compose.payment-simulation.yml`을 기본 compose와 함께 명시적으로 적용한다. 기본 compose 자체는 변경하지 않는다. 토큰은 충분히 긴 임의 값으로 설정하고 저장소나 로그에 기록하지 않는다. 실행 중인 이미지에는 이번 소스가 포함되어야 한다.

```bash
# 같은 환경 변수 값을 payment-service와 시나리오 실행 쉘에 제공한다.
export PAYMENT_SIMULATION_TOKEN='<로컬 전용 임의 토큰>'
# 로컬 compose 선택: -f docker-compose.yml -f docker-compose.payment-simulation.yml
./scripts/test-01-e2e-order-payment-flow.sh
```

직접 HTTP 예시 (Gateway):

```bash
curl -X POST "$BASE/payments/simulation/webhook" \
  -H "Authorization: Bearer $BUYER_TOKEN" \
  -H "X-Payment-Simulation-Token: $PAYMENT_SIMULATION_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"orderId":"<주문 UUID>","result":"SUCCESS","pgTxId":"LOCAL-<고유 ID>","amount":58000}'
```

기존 E2E/멱등성/Saga 스크립트는 이 보호된 시뮬레이션 helper를 사용한다. 실제 Kafka와 전체 compose 시나리오 실행은 웹 계층·실제 PostgreSQL 기반 단위/통합 검증과 별개다.
