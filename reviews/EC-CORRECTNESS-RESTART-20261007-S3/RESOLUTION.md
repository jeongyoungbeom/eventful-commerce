# 3단계 리뷰 반영 완료 — 보상 롤백 후 재고와 취소 기록 복구

Task ID: EC-CORRECTNESS-RESTART-20261007-S3
반영일: 2026-10-19 (Asia/Seoul)
역할: workflow-apply-review
대상: `/home/jybeomss/sideProject`
기준 커밋: `96c0e240420ef003e363d7078410dc3a34abc741`

## REV-001 — ACCEPTED

### 판정과 근거

리뷰의 전제와 현재 코드가 일치한다. 주문 행 잠금과 DB 트랜잭션이 Redis의 commit/restock을 되돌리지 않는데, 기존 취소는 DB의 RESERVED만 보고 release를 호출했다. 실제 Redis가 COMMITTED/RESTOCKED이면 release가 TERMINAL_CONFLICT를 반환하므로 복구가 막힌다. 또한 Redis 복원이 이미 끝난 재시도에서 ALREADY_APPLIED를 이유로 재고 이벤트를 생략하면, 이전 DB 롤백으로 사라진 Outbox가 복구되지 않는다.

신규 정상 회귀 테스트를 먼저 적용해 수정 전 12개 모두 실패함을 확인했다. 11개는 TERMINAL_CONFLICT로 복구가 실패했고, 확정 주문 취소 재시도 1개는 재고 이벤트가 2개 대신 0개였다. 기존 독립 리뷰의 진단을 그대로 반복하지 않고, 필요한 복구 경로와 이벤트 유실까지 정상 통합 테스트로 고정했다.

### 변경 내용

- `OrderCancelExecutor`의 RESERVED 취소는 release의 TERMINAL_CONFLICT에 한해 기존 restock Lua로 COMMITTED/RESTOCKED를 처리한다. restock은 예약 ID·상품·수량·현재 상태를 검증하고 이미 복원됐으면 재고를 다시 증가시키지 않는다. NOT_FOUND/CORRUPTED와 restock 실패는 계속 예외로 남겨 DB·Outbox를 롤백한다.
- DB에 아직 취소되지 않은 품목은 재고 결과가 APPLIED 또는 ALREADY_APPLIED이면 재고 이벤트를 같은 트랜잭션에 기록한다. 실제 사용한 복원 동작에 따라 RELEASED/RESTOCKED 사유를 유지한다. 주문 행 잠금과 CANCELED 대상 제외가 커밋된 취소의 중복 이벤트를 막는다.
- 결제 재시도가 이미 복원된 예약을 만나면 기존 commit Lua가 거절하고 기존 실패 보상 경로로 수렴한다. 확정 이벤트 기록 실패의 DB 롤백과 정상 commit 재시도, DB 잠금 아래 직접 보상하는 호출 순서는 유지한다.
- 제품 코드는 `order-service/src/main/kotlin/com/eventfulcommerce/order/service/OrderCancelExecutor.kt` 한 파일만 변경했다. 회귀 테스트는 기존 `OrderReservationPostgresIntegrationTest.kt`와 `OrderCancelExecutorTest.kt`에 추가했다. 스키마·Lua·공통 계약·배송 제품 코드는 수정하지 않았다.

### 실제 검증 결과

```bash
./gradlew :order-service:build :shipping-service:build --offline --no-daemon --console=plain --max-workers=2
```

BUILD SUCCESSFUL in 38s. 주문 테스트 81개를 실제 실행했고 실패·오류·생략은 모두 0개다. PostgreSQL·Redis 주문 통합 테스트는 기존 22개에 신규 12개를 더한 34개, 취소 executor 단위 테스트는 7개다. 배송의 기존 테스트 4개와 빌드는 UP-TO-DATE 결과를 사용했다. 실제 배송 소비자 연계는 주문 통합 테스트에서 실행했다.

추가로 검증한 내용:

1. 일부 Redis commit 후 보상 취소 payload 직렬화 실패: DB 판매자·품목 RESERVED, 사가·Outbox·eventId marker 롤백, Redis RESTOCKED/RELEASED 잔존 확인.
2. 첫 재고 복원 이벤트 직렬화 실패: DB 롤백과 Redis RESTOCKED/RESERVED 혼재 확인.
3. 위 두 실패 시점 각각에서 같은 결제 eventId, 다른 eventId 재발행, 만료, 전체 취소, 판매자별 취소의 10개 복구 시나리오 검증. 이전 취소 보존, 모든 대상 취소, 판매자별 정확한 환불 금액, 예약별 재고 이벤트 한 건, 재고 10·holdCount 0, 추가 확정 이벤트 없음, 반복 호출의 추가 효과 없음 확인. 결제 재시도 경로는 사가 REFUND_PENDING·신규 환불 대기 수 2와 marker를 확인했다.
4. 확정 이벤트 기록 실패로 DB RESERVED/Redis COMMITTED가 된 뒤 사용자 취소가 한 번만 재고를 복원하는지 확인.
5. DB CONFIRMED 주문의 취소 기록 롤백 뒤 재시도가 재고 이벤트도 복구하는지 확인.
6. RESERVED 복구의 restock 검증 실패가 취소·Outbox 저장을 막는 단위 검증 추가. 기존 일반 확정 재시도·경합·실제 DB Lock 대기·잠금 순서·실제 배송 수신자 검증도 통과했다.

근거는 `.workflow/results/EC-CORRECTNESS-RESTART-20261007-S3/apply-review/`에 보존했다. `regression-before-fix.log`·`test-results-before-fix/`는 수정 전 실패, `build.log`·`test-results/`는 수정 후 성공이다. `validation.json`과 `source-manifest.json`에 집계와 소스 hash를 남겼다. 기존 소스 기준선 395개 중 제품 1개·테스트 2개만 변경했고 나머지 392개는 동일하다. git diff --check를 통과했다.

### 완료와 범위

REV-001은 수정·회귀 검증 완료이며 현재 완료 조건을 위반하는 미해결 finding은 없다. 기존 독립 리뷰와 이번 반영 결과로 S3 TASK를 completed, HANDOFF를 inactive로 바꾼다. 다음 별도 개발 세션은 4단계 배송 상태에 따른 취소·환불·재고 복원 연결이다. 4~6단계는 미착수다.

기존 S1·S2 변경과 리뷰·검증 자료를 보존했다. 테스트는 격리 PostgreSQL·Redis를 사용했고 실행 후 종료했다. 기존 DB 초기화·배포·커밋은 수행하지 않았다. 실제 Kafka 전달·DLT·외부 PG 호출과 비동기 배송 완료 worker는 실행하지 않았다.
