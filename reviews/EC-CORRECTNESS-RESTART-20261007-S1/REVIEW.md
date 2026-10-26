# 1단계 정산 환불 멱등성 독립 리뷰

Task ID: EC-CORRECTNESS-RESTART-20261007-S1
Workflow: workflow-independent-review
검토일: 2026-10-08 (Asia/Seoul)
대상: `/home/jybeomss/sideProject`
기준 커밋: `96c0e240420ef003e363d7078410dc3a34abc741`

## 검토 범위

1단계의 미커밋 정산 제품·저장소·스키마·테스트 변경과 payment 재발행 계약 테스트를 검토했다. 직접 영향을 받는 정산 생성·지급·배치 writer, `IdempotencyHandler`, payment의 환불 생성·DB 저장·Saga 재발행 흐름도 필요한 범위에서 대조했다. 구현에 참여하지 않은 이번 세션에서 제품 코드·테스트를 수정하지 않고 리뷰했다.

## 요구사항 출처

- 사용자의 이번 세션 요청과 `AGENTS.md`, `.agents/skills/workflow-independent-review/SKILL.md`.
- active `.workflow/TASK.md`의 완료 조건 1~7과 `.workflow/COMMERCE_REMEDIATION_PLAN.md`의 1단계.
- active DECISIONS D-001~D-004 및 해당 Task ID에 적용되는 D-005. 나노초 문자열 보존은 정산 쪽 손실을 막지만 payment 쪽 저장·재조회 손실까지 해결하지 않는다는 차이를 발견했다.
- Task ID가 일치하는 기존 active HANDOFF와 DEVELOPMENT·validation 산출물을 실제 코드·XML과 대조한 뒤 인수하고 기존 인계를 inactive로 바꿨다. 과거 Task ID의 리뷰·반영 결과를 사용하지 않았다.

## 수행한 검증

1. 현재 제품·테스트 diff와 새 파일을 포함한 SHA-256은 `de8023efa0bc7be698b36af9f8ae573fc927f41140a8fd31f38b41ec8f638024`로, 개발 세션의 `validation.json`과 정확히 일치한다. 기준 HEAD와 `.gitignore`의 기존 변경도 일치했다.
2. 기존 최종 Gradle 로그의 `BUILD SUCCESSFUL in 19s`와 실제 XML을 확인했다. 정산 26개(실제 PostgreSQL 통합 18개), payment 13개, 합계 39개이며 실패·오류·생략은 0개다. 이는 **개발 세션의 실행 결과**다. 이번 세션에서 동일 테스트·빌드를 재실행하지 않았다.
3. `git diff --check`를 실행해 통과했다. 행 잠금, refundId PK, 새 기록 insert, version 보호, 양수·한도·누적 범위, 원본 내용 검증과 로컬 트랜잭션·예외 전파를 코드 및 기존 테스트와 대조했다.
4. 새 의심 근거인 최초 발행과 DB 재조회 후 발행의 시각 차이만 별도 재현했다. Compose와 분리된 임시 `postgres:16-alpine`, 현재 JDBC 42.7.4, 기존에 컴파일된 제품 `SettlementRefund.matches`를 사용했다. 실제 DB에 timestamp를 저장하고 재조회한 시각으로 같은 refundId의 payload를 만들었을 때 비교가 false였다. 재현 프로세스는 종료 코드 0이며 임시 컨테이너를 제거했다. 결과는 같은 폴더의 `timestamp-reproduction.log`에 보존했다.
5. 이번 세션 전후 제품·테스트·기존 `.gitignore`의 hash와 XML 집계를 대조해 보존을 확인했다. 근거는 같은 폴더의 `review-validation.json`이다.

## 검토 제외 범위

2~6단계 구현·결함 재현, 전체 저장소 빌드, ARL·실험 코드, 기존 데이터 이전·receipt backfill·legacy 호환, prod 배포·운영 스키마 전환은 제외했다. 새 V1의 기준 테이블 전제는 기존 `docs/schema-migration-policy.md`에 따른 증분 스키마로 검토했으며 이번 단계에서 전체 prod 기준선을 추가하지 않는다.

## 미검증 항목

- 새 재현은 실제 PostgreSQL/JDBC 저장·재조회와 컴파일된 제품 비교 메서드 검증이다. 실제 Spring payment 환불 생성 → 트랜잭션 종료 → 별도 트랜잭션에서 재조회·재발행 → 정산 소비까지의 전체 통합 흐름은 실행하지 않았다. 기존 payment 테스트는 mock 객체의 `refundedAt`을 그대로 재사용하며, 정산 통합 테스트도 동일 메모리 payload를 재발행하므로 이 흐름을 대신 검증하지 못한다.
- 실제 Kafka 브로커를 통한 재시도·DLT 전달은 실행하지 않았다. 예외 전파·marker 롤백·DLT 설정은 코드로 확인했다.
- REV-001 수정과 수정 후 필수 회귀 검증은 다음 `workflow-apply-review` 세션의 남은 작업이다.

## 판정

**Major finding 1건. 1단계는 미완료이며 TASK를 active로 유지한다.** 다음 workflow는 `workflow-apply-review`다. 이번 세션에서 finding을 반영하거나 RESOLUTION을 작성하지 않았고, 2단계로 진행하지 않았다.

## REV-001 — DB 재조회한 정상 환불 재발행을 시각 불일치로 거부

- **심각도:** Major
- **위치:** `settlement-service/src/main/kotlin/com/eventfulcommerce/settlement/domain/entity/SettlementRefund.kt:47-49` 및 `settlement-service/src/main/kotlin/com/eventfulcommerce/settlement/service/SettlementService.kt:67-69`.
- **문제:** 처리 기록은 최초 payload의 나노초 `refundedAt.toString()`을 저장하고 재발행에도 완전 일치를 요구한다. 그러나 실제 발행자는 최초 생성한 `PaymentRefund`의 메모리 시각과 PostgreSQL 저장 후 재조회한 시각을 각각 발행한다. 저장 정밀도가 달라지면 같은 환불 사실도 다른 내용으로 판정한다.
- **발생 조건:** `PaymentRefund.refundedAt`에 마이크로초 이하 자릿수가 있는 환불을 최초 발행·정산 처리한 뒤, 별도 트랜잭션의 Saga 대사가 같은 환불을 DB에서 읽어 다른 eventId로 재발행한다. 새 환불은 `Instant.now()`를 사용하므로 일반적인 생성 경로에서 발생할 수 있다.
- **영향:** 정상 재발행이 정상 중복으로 종료되지 않고 `IllegalArgumentException`을 발생시킨다. 새 eventId marker는 롤백되며 재시도해도 같은 불일치가 남는다. 기존 Kafka 설정에서는 재시도 후 DLT 대상이 된다. 추가 차감 자체는 막지만 정상 환불 사실의 재발행·복구 경로가 실패하므로 단계의 재발행 요구를 충족하지 못한다.
- **근거:**
  - `payment-service/.../domain/entity/PaymentRefund.kt:32-33`은 `Instant.now()`를 timestamp 컬럼에 저장한다.
  - `payment-service/.../service/PaymentService.kt:123-148,170-190`은 새 엔티티를 저장한 뒤 메모리에 있는 `refund.refundedAt`을 최초 Outbox payload로 직렬화한다. DB 값을 refresh하지 않는다.
  - 같은 파일 `153-163`은 대사 시 저장소에서 기존 환불을 다시 읽고 같은 발행 함수를 호출한다.
  - 격리 재현의 실제 결과는 다음과 같다. refundId·정산·금액·사유는 동일하고 시각만 DB 저장·재조회 때문에 달라졌다.

    ```text
    first_payload.refundedAt=2026-10-07T12:34:56.123456789Z
    republished_payload.refundedAt=2026-10-07T12:34:56.123457Z
    receipt.matches(first_payload)=true
    receipt.matches(republished_payload)=false
    ```

  - payment 재발행 테스트의 새 단언은 기존 mock 엔티티와 예상 payload가 같은 `refundedAt`을 공유한다. 정산 통합 테스트 `90-101`도 동일 payload를 재사용한다. 따라서 기존 39개 통과는 DB 왕복 후의 불변성을 입증하지 않는다.
- **권장 수정:** 최초 저장·최초 발행·DB 재조회 후 발행이 모두 같은 시각 값을 사용하도록 생성 시점에 저장 가능한 정밀도로 정규화하거나, 불변 원본 시각을 보존·재사용하는 최소 계약을 마련한다. timestamp 비교를 단순히 제거해 실제 내용 불일치를 허용하지 않는다. 수용 시 D-005의 정밀도 전제도 최종 구현에 맞춰 갱신한다.
- **필요한 후속 검증:** 실제 PostgreSQL·Spring payment 저장소/트랜잭션에서 환불 생성과 최초 발행을 완료한 뒤 영속성 컨텍스트가 다른 트랜잭션으로 재조회·재발행하고 정산에 전달한다. 처리 기록 1개·금액 차감 1회·두 eventId의 정상 종료를 확인한다. 금액·사유·실제 다른 환불 시각·식별자 불일치 거부와 직접 영향을 받는 정산/payment 검증을 유지한다.
