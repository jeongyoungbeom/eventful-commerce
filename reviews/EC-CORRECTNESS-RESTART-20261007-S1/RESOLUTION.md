# 1단계 정산 환불 멱등성 리뷰 반영 결과

Task ID: EC-CORRECTNESS-RESTART-20261007-S1
Workflow: workflow-apply-review
반영일: 2026-10-08 (Asia/Seoul)
대상: `/home/jybeomss/sideProject`
기준 커밋: `96c0e240420ef003e363d7078410dc3a34abc741`

## 최종 판정

REV-001(Major)을 `ACCEPTED`로 판정하고 수정·필수 회귀 검증을 완료했다. 현재 TASK 완료 조건을 위반하는 미해결 finding은 없다. 독립 리뷰와 필요한 반영이 끝나 1단계와 TASK를 `completed`, HANDOFF를 `inactive`로 갱신했다. 이번 세션은 `workflow-apply-review`만 수행했고 2~6단계는 미착수다.

## REV-001 — ACCEPTED

**재검증 근거:** 시작 시 HEAD와 REVIEW에 기록된 제품·테스트 파일 SHA-256을 모두 대조해 검토 당시 코드와 일치함을 확인했다. `PaymentRefund`는 `Instant.now()`의 나노초 시각을 유지하고, 최초 Outbox는 이 메모리 값을 직렬화한다. 대사는 별도 트랜잭션에서 PostgreSQL timestamp 값을 읽는다. `SettlementRefund.matches`는 최초 원본 문자열과 완전 비교하므로 REVIEW의 `.123456789Z → .123457Z` 차이는 정상 재발행을 거부한다. 현재 요구사항의 정상 재발행 완료 조건에 해당하는 실제 문제다. 기존 JDBC 재현을 같은 근거로 반복하지 않았다.

**변경:** `PaymentRefund` 생성 시 입력 시각을 `ChronoUnit.MICROS`로 절삭한다. 기본 현재 시각과 명시적으로 전달한 시각 모두 같은 계약을 따른다. PostgreSQL 저장 전에 정규화하므로 최초 저장·최초 Outbox·DB 재조회·대사 Outbox가 같은 환불 시각을 사용한다. DB 반올림으로 다음 마이크로초·다음 초로 이동하는 차이도 생성 시 제거한다. 정산의 시각 문자열 보존·완전 비교는 그대로 유지하여 실제 다른 시각·금액·사유·식별자 거부를 보존했다. D-005를 이 발행자 정밀도 계약에 맞춰 갱신했다.

**회귀 검증:** 고정 나노초 입력 `.123456789Z`, 초 경계 `.999999999Z`, 이미 마이크로초인 입력의 생성 결과를 결정적으로 검증하는 `PaymentRefundTest`를 추가했다. 기존 정산 PostgreSQL 통합 테스트에 실제 payment 제품 클래스를 test dependency로 연결했다. 제품 runtime dependency는 추가하지 않았다. 실제 저장소와 Spring 서비스·멱등성 프록시를 사용해 주문 취소 → 환불 생성·최초 Outbox 커밋 → 다른 영속성 컨텍스트의 DB 조회 → Saga 대사 재조회·재발행 커밋 → 정산 소비를 검증한다. 테스트 자체의 외부 트랜잭션은 없으며 각 서비스 호출은 별도로 커밋한다.

두 Outbox의 eventId는 다르고 전체 환불 payload는 동일하다. 환불 시각은 저장·조회 전후 동일하며, payment 환불 기록 1개·정산 처리 기록 1개·차감 1회·정산의 두 eventId marker 정상 저장을 확인했다. 최초 eventId 재전달도 추가 차감하지 않았다. 정산에 다른 시각을 단순 허용하거나 비교를 제거하지 않았다.

## 실제 검증 결과

```sh
./gradlew :payment-service:test :settlement-service:test --no-daemon --console=plain --max-workers=2
./gradlew :payment-service:build :settlement-service:build --no-daemon --console=plain --max-workers=2
```

첫 명령은 `BUILD SUCCESSFUL in 27s`이며 두 테스트 task가 실제 실행됐다. 두 번째 명령은 `BUILD SUCCESSFUL in 8s`다. 변경된 payment 실행 JAR를 생성했고 정산 빌드도 성공했다. 두 번째 명령의 테스트는 동일 코드에서 이미 통과했으므로 `UP-TO-DATE` 결과를 사용했으며 재실행으로 보고하지 않는다.

| 검증 | 테스트 수 | 실패 | 오류 | 생략 |
|---|---:|---:|---:|---:|
| payment 생성 시각·서비스·웹훅 | 14 | 0 | 0 | 0 |
| 정산 소비자·서비스 | 8 | 0 | 0 | 0 |
| 실제 PostgreSQL 통합 | 19 | 0 | 0 | 0 |
| 합계 | 41 | 0 | 0 | 0 |

기존 동일·서로 다른 환불 경합 반복 3회씩, 금액·사유·나노초 시각·식별자 불일치, 잔여 한도, eventId·환불 기록·금액 동반 롤백, PK·FK·CHECK, 오래된 writer 충돌도 수정 후 실행에서 통과했다. 실제 XML을 집계했으며 `git diff --check`도 통과했다. 로그는 `review-tests.log`, `review-build.log`, 파일 hash·XML 집계는 `resolution-validation.json`이다. 최종 제품·테스트 파일 hash manifest SHA-256은 `0fcc950d81fd152e7ee848076bf232af0b3cf14e11d7462804b30e4398a0298e`다. 이 값은 파일별 hash JSON의 SHA-256으로 이전 개발 세션의 diff hash와 계산 방식이 다르다.

## 완료 조건과 보존

TASK 조건 1은 실제 발행자의 저장·재조회·재발행 검증으로 보완했다. 조건 2~6의 기존 경합·금액·제약·롤백 검증도 이번 수정 후 모두 통과했고, 조건 7의 독립 리뷰와 ACCEPTED 반영·최종 검증이 끝났다. 남은 필수 작업·필수 검증은 없다.

기존 미커밋 제품·테스트 변경과 사용자의 `.gitignore` 변경을 보존했다. 기존 파일 중 이번 반영으로 변경한 정산 build 설정과 통합 테스트는 회귀 검증 연결·추가에 한정했고, 나머지 이전 파일 hash는 그대로임을 확인했다. REVIEW와 기존 DEVELOPMENT·재현 근거를 덮어쓰지 않았다. 커밋·배포·개발용 Compose 서비스 및 DB 초기화·재시작은 수행하지 않았다. PostgreSQL 통합 검증은 Testcontainers가 만든 격리 DB만 사용했다.

실제 Kafka 브로커 전달·재시도·DLT는 이번 검증에서 실행하지 않았다. Outbox의 제품 payload와 정산 소비자를 직접 연결해 완료 조건의 DB·트랜잭션 경로를 검증했다. 2~6단계, ARL, legacy backfill은 이번 범위가 아니다. 다음 별도 세션에서만 `workflow-develop`으로 2단계 TASK를 작성·진행한다.
