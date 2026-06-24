# 장애 및 실패 시나리오 대응

Eventful Commerce는 주문, 결제, 재고, 환불 흐름이 여러 서비스와 Kafka 이벤트를 통해 이어지는 구조입니다. 이 문서는 실패 상황별 위험, 대응 방식, 검증 근거를 정리합니다.

## 검증 범위

| 구분 | 검증 대상 |
|---|---|
| 단위 테스트 | 주문 생성, 결제 예약, 결제 완료, 주문 취소, 환불, 멱등성, Outbox 상태 전이 |
| 시나리오 스크립트 | E2E 주문/결제, 재고 초과 판매 방지, 중복 취소, 중복 결제 이벤트, Outbox 상태 전이 |

실행 명령:

```bash
./gradlew :payment-service:test :common-outbox:test :common-idempotency:test :order-service:test
./scripts/verify.sh
```

## 1. 재고 부족 및 부분 주문 실패

상황:
- 사용자가 여러 상품을 주문했지만 일부 상품의 재고가 부족하거나 판매 불가 상태일 수 있습니다.

위험:
- 재고가 없는 상품까지 주문에 포함되면 결제, 배송, 정산 이벤트가 잘못 이어집니다.
- 모든 상품이 실패했는데 주문 레코드가 남으면 후속 이벤트가 불필요하게 발생할 수 있습니다.

대응:
- `order-service`는 상품별로 `ProductReadModel` 상태를 확인합니다.
- 판매 불가 상품은 재고 예약을 시도하지 않고 `failedItems`에 포함합니다.
- Redis 재고 예약에 실패한 상품은 `INSUFFICIENT_STOCK`으로 분리합니다.
- 예약 성공 상품이 하나도 없으면 임시 주문을 삭제하고 `ORDER_FAILED` 응답을 반환합니다.

검증:
- `OrdersServiceTest`
  - 일부 상품 예약 실패 시 성공 상품만 주문하고 실패 상품을 응답
  - 모든 상품 예약 실패 시 임시 주문 삭제
  - 판매 불가 상품은 재고 예약 미시도
- `scripts/test-02-stock-oversell-traffic.sh`
  - 대량 동시 주문에서도 성공 수가 초기 재고를 초과하지 않음

## 2. 재고 초과 판매

상황:
- 동일 상품에 대해 짧은 시간에 많은 주문 요청이 몰릴 수 있습니다.

위험:
- DB 조회 후 차감 방식으로 처리하면 동시성 경쟁으로 재고보다 많은 주문이 성공할 수 있습니다.

대응:
- Redis Lua 스크립트로 재고 확인과 예약 차감을 원자적으로 처리합니다.
- 주문 확정 전에는 예약 재고로 보관하고, 결제 완료 시 commit합니다.
- 주문 취소나 결제 실패 시 예약 재고를 release합니다.

검증:
- `OrdersServiceTest`
  - 재고 예약 성공 시 주문 생성
  - 재고 예약 실패 시 실패 상품 분리
- `OrderCancelExecutorTest`
  - 예약 주문 취소 시 `InventoryReservationService.release()` 호출
- `OrdersServiceTest`
  - 결제 완료 시 `InventoryReservationService.commit()` 호출
- `scripts/test-02-stock-oversell-traffic.sh`
  - 요청 5,000건, 동시성 400, 초기 재고 1,000개 기준 초과 판매 0건 검증

## 3. 결제 완료 이벤트 중복 수신

상황:
- Kafka at-least-once 특성상 `PAYMENT_COMPLETED` 이벤트가 중복 전달될 수 있습니다.

위험:
- 같은 결제 완료 이벤트를 여러 번 처리하면 재고 commit, 주문 확정, 후속 Outbox 이벤트가 중복 실행됩니다.

대응:
- `common-idempotency`의 `IdempotencyHandler`가 eventId를 `processed_event`에 먼저 저장합니다.
- eventId가 이미 존재하면 `AlreadyProcessed`로 판단하고 비즈니스 action을 실행하지 않습니다.
- 주문이 이미 `ORDER_CONFIRMED` 상태이면 재고 commit과 Outbox 기록을 다시 수행하지 않습니다.

검증:
- `IdempotencyHandlerTest`
  - 최초 이벤트는 action 실행
  - 중복 이벤트는 action 미실행
  - action 예외는 호출자에게 전파
- `OrdersServiceTest`
  - 결제 완료 이벤트 처리 시 재고 commit 및 `ORDER_CONFIRMED` 이벤트 기록
  - 이미 확정된 주문은 commit/outbox 재실행 방지
- `scripts/test-04-payment-event-idempotency.sh`
  - 동일 `PAYMENT_COMPLETED` 이벤트 대량 재주입 후 주문 확정 이벤트 1회 검증

## 4. 결제 웹훅 중복 수신

상황:
- PG 또는 외부 결제 시스템이 같은 결제 결과 웹훅을 여러 번 보낼 수 있습니다.

위험:
- `PAYMENT_COMPLETED` 또는 `PAYMENT_FAILED` 이벤트가 중복 발행될 수 있습니다.

대응:
- `payment-service`는 `PAYMENT_RESERVED` 상태인 결제만 웹훅 처리 대상으로 봅니다.
- 이미 완료 또는 실패 처리된 결제는 웹훅을 다시 받아도 Outbox 이벤트를 기록하지 않습니다.

검증:
- `PaymentWebhookServiceTest`
  - 성공 웹훅은 `PAYMENT_COMPLETED` 이벤트 기록
  - 실패 웹훅은 `PAYMENT_FAILED` 이벤트 기록
  - 예약 상태가 아닌 결제는 중복 웹훅을 받아도 이벤트 미기록
  - 결제 정보가 없으면 예외 발생 및 이벤트 미기록

## 5. 주문 취소 중복 요청

상황:
- 사용자가 취소 버튼을 여러 번 누르거나 네트워크 재시도로 동일 주문 취소 요청이 중복될 수 있습니다.

위험:
- 예약 재고 release 또는 확정 재고 보정이 여러 번 실행될 수 있습니다.
- 환불 이벤트가 중복 발행될 수 있습니다.

대응:
- `OrderCancelService`는 Redisson 분산락으로 동일 주문 취소 실행을 직렬화합니다.
- `OrderCancelExecutor`는 이미 취소된 seller order를 취소 대상에서 제외합니다.
- 취소 가능한 대상이 없으면 재고 처리, 주문 저장, Outbox 기록을 수행하지 않습니다.

검증:
- `OrderCancelExecutorTest`
  - 예약 주문 취소 시 재고 release
  - 확정 주문 취소 시 재고 보정
  - 이미 취소된 주문은 재고 처리와 Outbox 기록 미수행
  - 판매자 주문 부분 취소 시 대상만 취소되고 주문 상태는 `ORDER_PARTIALLY_CANCELED`
- `scripts/test-03-order-cancel-lock-traffic.sh`
  - 대량 중복 취소 요청 중 1건만 성공하는지 검증

## 6. 주문 취소 후 중복 환불

상황:
- `ORDER_CANCELED` 이벤트가 중복 전달되거나 같은 seller order 취소 이벤트가 다시 처리될 수 있습니다.

위험:
- 같은 seller order에 대해 환불 레코드와 `PAYMENT_REFUNDED` 이벤트가 중복 생성될 수 있습니다.

대응:
- `payment-service`는 `payment_id + seller_order_id` 기준으로 기존 환불 레코드를 확인합니다.
- 이미 환불 레코드가 있으면 해당 seller order는 환불 대상에서 제외합니다.
- 결제가 아직 완료되지 않은 상태이면 주문 취소 이벤트를 받아도 환불하지 않습니다.

검증:
- `PaymentServiceTest`
  - 주문 취소 이벤트 수신 시 환불 레코드와 `PAYMENT_REFUNDED` 이벤트 생성
  - 부분 환불이면 `PAYMENT_PARTIALLY_REFUNDED` 상태로 변경
  - 기존 환불 레코드가 있으면 중복 환불 이벤트 미생성
  - 완료되지 않은 결제는 환불 미수행

## 7. Outbox 발행 실패

상황:
- DB 저장은 성공했지만 Kafka 발행이 일시적으로 실패할 수 있습니다.

위험:
- 이벤트가 유실되면 다음 서비스로 상태가 전파되지 않습니다.
- 실패 메시지가 과도하게 길면 운영 로그와 DB 저장에 부담이 됩니다.

대응:
- 도메인 상태 변경과 Outbox 이벤트 저장을 같은 트랜잭션 안에서 처리합니다.
- Outbox publisher가 `PENDING` 이벤트를 재시도합니다.
- 발행 성공 시 `SENT`로 전이합니다.
- 발행 실패 시 retry count를 증가시키고, 최대 재시도에 도달하면 `FAILED`로 전이합니다.
- 실패 메시지는 2,000자로 제한합니다.

검증:
- `OutboxEventServiceTest`
  - 이벤트 목록 저장
  - `markAsSent()`가 `SENT` 전이를 repository에 위임
  - `markAsFailed()`가 긴 에러 메시지를 2,000자로 제한
  - 예외 메시지가 없으면 예외 클래스명을 `lastError`로 사용
- `scripts/test-05-outbox-status-transition.sh`
  - 상품 등록 Outbox 이벤트가 `PENDING`에서 발행 결과 상태로 전이되는지 검증

## 8. 주문 예약 이벤트 중복 수신

상황:
- `ORDER_RESERVED` 이벤트가 payment-service에 중복 전달될 수 있습니다.

위험:
- 같은 주문에 대해 결제 예약 레코드가 중복 생성될 수 있습니다.

대응:
- `PaymentService.handleOrderCreated()`는 idempotency handler를 통해 eventId 중복을 방어합니다.
- action 내부에서도 `orderId`로 기존 결제 레코드를 조회하고, 이미 존재하면 새 결제를 생성하지 않습니다.

검증:
- `PaymentServiceTest`
  - `ORDER_RESERVED` 이벤트 수신 시 `PAYMENT_RESERVED` 결제 레코드 생성
  - 이미 결제가 존재하면 결제 레코드 미생성

## 테스트 근거 요약

| 테스트 | 검증 수 |
|---|---:|
| `OrdersServiceTest` | 6 |
| `OrderCancelExecutorTest` | 4 |
| `PaymentServiceTest` | 6 |
| `PaymentWebhookServiceTest` | 4 |
| `IdempotencyHandlerTest` | 3 |
| `OutboxEventServiceTest` | 4 |
| 합계 | 27 |

최근 검증 명령:

```bash
./gradlew :payment-service:test :common-outbox:test :common-idempotency:test :order-service:test
```

결과:

```text
BUILD SUCCESSFUL
27 tests, 0 failures
```
