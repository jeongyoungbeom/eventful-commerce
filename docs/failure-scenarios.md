# 장애·보상·운영 검증 시나리오

이 문서는 Eventful Commerce의 주문-재고-결제-배송 흐름에서 **유실, 중복, 순서 역전**이 발생했을 때의 상태 전이와 운영 절차를 정의한다. 모든 서비스는 독립 DB를 사용하며, 서비스 간 정합성은 분산 트랜잭션이 아니라 Kafka 이벤트 기반 Saga로 맞춘다.

## 신뢰성 경계

| 문제 | 구현 위치 | 보장 |
|---|---|---|
| 같은 HTTP 주문 요청 재시도 | `order_request_idempotency` | 동일 `Idempotency-Key`는 같은 요청 결과만 반환한다. 다른 본문 재사용은 거부한다. |
| Redis 예약 중복·고아 hold | Redis Lua + 만료 스케줄러 | 같은 reservation은 한 번만 차감한다. 주문 DB가 없거나 만료된 hold는 release한다. |
| DB 저장 후 Kafka 발행 실패 | `outbox_event` | 도메인 저장과 Outbox 저장은 한 DB 트랜잭션이다. 발행은 별도 publisher가 재시도한다. |
| Kafka 중복 소비 | `processed_event` | 소비자는 eventId를 먼저 선점한다. 이미 처리한 이벤트는 비즈니스 로직을 실행하지 않는다. |
| 서비스 간 보상 | `order_saga` | 주문 서비스가 주문별 Saga 상태와 예상 환불 수를 보관한다. 각 서비스는 자신의 DB와 수신 이벤트만 변경한다. |

## 정상 흐름

1. order-service는 Redis Lua로 수량을 예약하고 `Order`, `SellerOrder`, `OrderItem`, `OrderSaga(RESERVED)`, `ORDER_RESERVED` Outbox를 한 트랜잭션에 저장한다.
2. payment-service는 `ORDER_RESERVED`를 멱등 소비해 `PAYMENT_RESERVED` 결제를 저장한다.
3. PG 성공 웹훅은 `PAYMENT_COMPLETED` Outbox를 만들고 결제 상태를 완료로 바꾼다.
4. order-service는 결제 완료를 멱등 소비해 Redis hold를 commit하고 주문/Saga를 `CONFIRMED`로 바꾼 뒤 `ORDER_CONFIRMED`를 발행한다.
5. shipping-service와 settlement-service는 `ORDER_CONFIRMED`를 소비해 각각 배송과 정산을 만든다.

## 실패·보상 시나리오

### 1. Redis 예약 뒤 주문 DB 저장 실패 또는 프로세스 종료

- Redis hold에는 reservation ID와 TTL이 기록된다.
- DB 트랜잭션 실패 시 즉시 release를 시도한다.
- 즉시 release가 실패하거나 프로세스가 종료되면 `InventoryReservationCleanupScheduler`가 만료/고아 hold를 찾아 원자적으로 release한다.
- 검증 기준: 가용 재고와 hold 집계가 원래 값으로 회복되고, 주문 DB에 해당 reservation의 유효 주문이 없어야 한다.

### 2. Outbox 발행 실패·중복 발행

- publisher는 `PENDING` 이벤트를 claim token과 `PROCESSING` 상태로 원자적으로 선점한다.
- Kafka 전송 성공은 동일 claim token일 때만 `SENT`로 전이한다. 오래된 publisher의 늦은 콜백은 상태를 덮어쓰지 못한다.
- 전송 실패는 지수 backoff로 재시도한다. 최대 횟수를 넘으면 `FAILED`로 남기며 삭제하지 않는다.
- 프로세스 종료로 `PROCESSING`에 멈춘 행은 lease timeout 이후 다시 `PENDING`으로 복구한다.
- 결과적으로 Kafka에는 중복 전송될 수 있으므로, 소비자 `processed_event` 멱등성이 최종 방어선이다.

### 3. Outbox 실패 이벤트 운영 재처리

1. Prometheus의 `eventful_outbox_events{status="FAILED"}` 경보를 확인한다.
2. 각 서비스의 `GET /internal/outbox/failed`로 실패 원인과 retry count를 조회한다. 이 API는 `outbox.operations.enabled=true`와 `X-Outbox-Operations-Token`이 모두 필요하다.
3. Kafka/설정/데이터 원인을 먼저 해결한다.
4. `POST /internal/outbox/failed/{eventId}/requeue`를 호출한다. 행은 새로 만들지 않고 같은 eventId를 `PENDING`으로 되돌리며 재처리 횟수와 시각을 감사 필드에 남긴다.
5. `SENT` 또는 다시 `FAILED`가 되는지 지표와 DB를 확인한다.

재처리로 같은 Kafka 메시지가 다시 전달되어도 consumer의 `processed_event(event_id PK)`가 비즈니스 로직의 중복 실행을 차단한다.

### 4. 결제 완료 후 재고 확정 또는 배송 생성 실패

- 재고 commit 중 하나라도 실패하면 order-service는 Saga를 `COMPENSATION_REQUESTED`로 바꾸고 아직 예약인 item은 release, 이미 확정된 item은 restock하는 취소 경로를 실행한다.
- 취소 Outbox의 `ORDER_CANCELED`는 payment-service로 전달된다. 결제가 완료됐다면 seller order별 `PaymentRefund`와 `PAYMENT_REFUNDED` Outbox를 만든다.
- shipping-service의 비동기 배송 생성 실패도 `SHIPPING_FAILED` Outbox로 발행한다. order-service는 해당 seller order만 취소하고 환불을 기다린다.
- `PAYMENT_REFUNDED`가 모두 도착하면 Saga는 `COMPENSATED`가 된다. 예상 환불 개수보다 적으면 `REFUND_PENDING`에 남아 경보 대상이다.

### 5. 주문 취소가 결제 완료 웹훅보다 먼저 도착하는 순서 역전

이 경합은 "예약 상태라 환불하지 않음"으로 끝내면 안 된다.

1. payment-service가 `ORDER_CANCELED`를 수신했을 때 결제가 `PAYMENT_RESERVED`이면, 취소 payload를 `pending_cancellation_payload`에 저장하고 `cancellation_requested=true`로 남긴다.
2. 이후 성공 웹훅이 오면 같은 트랜잭션에서 결제를 완료 처리한 뒤 pending 취소를 즉시 환불로 전환한다.
3. 환불 Outbox가 만들어지고, 처리 후 pending payload와 취소 요청 flag는 제거된다.
4. 웹훅이 먼저 도착한 경우에는 기존 완료 결제 환불 경로가 실행되므로 두 순서 모두 안전하다.

환불 레코드는 `(payment_id, seller_order_id)`의 중복 확인으로 한 번만 생성된다. 취소와 웹훅이 중복되어도 재환불되지 않는다.

## 관측과 보존

| 신호 | 의미 | 초기 대응 |
|---|---|---|
| `eventful_outbox_events{status="FAILED"} > 0` | 자동 재시도를 소진한 발행 실패 | 실패 Outbox 조회 → 원인 해결 → 안전 재처리 |
| `eventful_saga_orders{status=~"COMPENSATION_REQUESTED|REFUND_PENDING|COMPENSATION_FAILED"} > 0` | 보상 절차가 완료되지 않음 | `order_saga.last_error`, 취소/환불 Outbox, 결제 상태를 orderId로 대조 |
| `up == 0` | 서비스 scrape 실패 | 인스턴스·DB·Kafka 연결 상태 확인 |

Prometheus rule은 `monitoring/prometheus/alerts.yml`에 있다. 현재 Compose 구성은 Prometheus UI에서 경보 상태를 확인하는 범위이며, Slack·PagerDuty 같은 외부 receiver는 운영 환경의 Alertmanager endpoint를 결정한 뒤 별도로 연결해야 한다.

- `SENT` Outbox는 기본 7일 후 정리한다. `FAILED`와 `PROCESSING`은 정리 대상이 아니다.
- `processed_event`는 기본 30일 후 정리한다. 이 기간은 Kafka 재처리 가능 기간과 최대 장애 복구 시간보다 길게 운영해야 한다.
- 두 정리 쿼리는 각각 `sent_at`, `processed_at` 인덱스를 사용한다.

## 검증 명령

```bash
# 핵심 단위·통합 모듈 검증
./gradlew :order-service:test :payment-service:test :shipping-service:test \
  :common-outbox:test :common-idempotency:test

# 실행 환경이 기동된 경우 정상 흐름·동시성·중복 이벤트·Outbox 상태 전이 검증
./scripts/verify.sh all
```

검증 결과는 `scripts/results/<run-id>/summary.md`와 `result.json`에 남는다. 장애 재현 중에는 `KEEP_TEST_DATA=1`로 실행해 DB 상태를 보존하고, 원인 확인이 끝난 뒤 명시적으로 정리한다.
