# 2단계 결제 경합·판매자별 대기 취소 독립 리뷰

Task ID: EC-CORRECTNESS-RESTART-20261007-S2
Workflow: workflow-independent-review
검토일: 2026-10-08 (Asia/Seoul)
대상: `/home/jybeomss/sideProject`
기준 커밋: `96c0e240420ef003e363d7078410dc3a34abc741`

## 검토 범위

2단계의 Payment 행 잠금·version, 판매자 주문별 대기 payload 배열, 성공/실패 웹훅의 단일 종결, 취소·대사·대기 환불, 금액 한도·내용 비교·로컬 트랜잭션·V3 SQL 및 직접 영향받는 테스트를 검토했다. 실제 주문 취소·Saga 대사 발행자, eventId 멱등 marker, 메시지 재시도 설정, 정산의 환불 원본 내용 비교와 payment 재조회·재발행 연계도 필요한 범위에서 대조했다. 구현에 참여하지 않은 이번 세션에서 제품 코드·테스트를 수정하지 않았다.

## 요구사항 출처

- 사용자의 이번 세션 요청, `AGENTS.md`, `.agents/skills/workflow-independent-review/SKILL.md`.
- active `.workflow/TASK.md`의 완료 조건 1~6과 `.workflow/COMMERCE_REMEDIATION_PLAN.md`의 2단계.
- 현재 작업에 적용되는 active D-001~D-004 및 task-scoped D-006. D-006의 결제/사용자·양수·기존 환불 판매자/금액 검증은 전액 환불 대사 분기에서 누락됨을 확인했다. S1의 D-005·REVIEW·ACCEPTED RESOLUTION은 환불 시각·정산 소비 계약의 관련 배경으로 읽었으며 S2 리뷰 결과로 재사용하지 않았다.
- Task ID가 같은 active HANDOFF와 DEVELOPMENT·validation.json·최종 로그를 현재 코드/hash/XML과 대조한 뒤 인수하고 개발 인계를 inactive로 종료했다. 종료 시 같은 Task ID의 새 active HANDOFF로 리뷰 반영에 인계했다.

## 수행한 검증

1. 현재 HEAD와 개발 manifest의 모든 파일별 SHA-256이 일치했다. 개발 manifest 식별 hash는 `eea36b0173b3d5e2c9edb01bb7e4f83dc8eb3aed332166ef817567573c7f0b57`이다. 이번 세션 시작 시 tracked·untracked 기존 파일 387개의 hash 기준선도 별도로 확보했다.
2. 개발 세션의 최종 `validation.log`에 두 서비스 테스트 task의 실제 실행과 `BUILD SUCCESSFUL in 28s`를 확인했다. 현재 XML은 payment 45개(실제 PostgreSQL 31개)·정산 27개(실제 PostgreSQL 19개), 합계 72개이며 실패·오류·생략은 0개다. 이는 **개발 세션의 실행 결과**다. 이번 리뷰에서 동일 테스트·빌드를 반복 실행하지 않았다.
3. 서비스·저장소·실제 caller와 기존 테스트를 대조했다. 공통 행 잠금, 오래된 writer의 version 충돌, 대기 A→B/B→A·최초 사유 보존, 종결 중복, Long 경계·잔여액 차감, checked 예외까지의 트랜잭션 롤백은 기존 검증과 코드가 일치한다. `git diff --check`도 통과했다.
4. 새 의심 근거인 전액 환불 대사 분기만 별도 재현했다. 기존 testRuntimeClasspath를 오프라인으로 조회하고, 별도 Java 재현 파일에서 현재 컴파일된 제품·기존 `PaymentConcurrencyPostgresIntegrationTest`의 Spring 설정을 사용했다. 격리 `postgres:16-alpine`에 V3를 적용하고 실제 Spring 서비스 프록시·Spring Data 저장소·독립 커밋으로 실행했다. 제품·테스트 소스는 변경하지 않았다. javac와 재현 JVM 종료 코드는 모두 0이다.
5. 재현은 부분/전액 환불 상태 각각에서 정상 대사 1건과 잘못된 입력 8건을 비교했다. 부분 환불의 잘못된 입력은 전부 예외·marker 미저장·Outbox 미추가였다. 전액 환불의 동일 종류 입력은 전부 정상 반환·marker 저장·원본 환불 Outbox 1개 추가였다. 금액/환불 기록은 두 상태 모두 변하지 않았다. 따라서 추가 차감이나 원본 환불 내용 변경으로 과장하지 않는다.
6. 세션 종료 전 기존 tracked·untracked 파일과 개발 manifest·HEAD·XML의 보존을 다시 확인했다. 격리 PostgreSQL은 종료됐고 `docker ps`에 실행 중인 컨테이너가 없다. 커밋·배포·Compose 서비스 재시작·개발 DB 초기화는 수행하지 않았다. 근거는 같은 폴더의 `review-validation.json`, `reconciliation-reproduction.log`, `FullyRefundedReconciliationReproduction.java`에 보존했다.

## 검토 제외 범위

3~6단계 구현 및 결함 재현, 별도 전체 저장소 빌드, ARL·실험 코드, 기존 데이터 이전·legacy JSON/OID 호환·receipt backfill, 운영 배포·prod 기준 스키마 작성은 제외했다. 웹훅 금액·거래·result·발신자 인증 계약은 계획의 6단계이므로 이번 finding으로 확장하지 않았다. V3는 기존 스키마 운영 정책의 증분 마이그레이션 전제로 검토했다.

## 미검증 항목

- 실제 Kafka 브로커 전달·재시도·DLT와 외부 PG는 실행하지 않았다. 예외 전파와 기존 설정은 코드로 확인했다.
- REV-001 수정 및 수정 후 실제 PostgreSQL·직접 영향받는 회귀 검증은 다음 별도 `workflow-apply-review` 세션의 남은 작업이다.

## 판정

**Minor finding 1건. 독립 리뷰는 완료했으나 2단계와 TASK는 미완료이며 active로 유지한다.** 개발 세션의 72개 통과는 유효하지만 전액 환불 대사의 잘못된 입력 거부까지 입증하지 못한다. TASK 완료 조건 4와 D-006의 검증 계약이 해당 분기에서 충족되지 않고, 조건 6의 필요한 리뷰 반영도 남아 있다.

다음 workflow는 `workflow-apply-review`다. `.agents/skills/workflow-independent-review/SKILL.md`의 “finding이 1개 이상이면 여기서 수정하지 않고 종료한다”와 사용자의 세션 역할 분리 지침에 따라 이번 세션에서는 수정·RESOLUTION 작성·3단계 구현을 수행하지 않았다.

## REV-001 — 전액 환불 대사가 사용자·판매자·금액 검증을 건너뜀

- **심각도:** Minor
- **위치:** `payment-service/src/main/kotlin/com/eventfulcommerce/payment/service/PaymentService.kt:95`, `199-210`. 비교 대상 검증은 같은 파일 `120-125`, `159-169`다.
- **문제:** `PAYMENT_COMPLETED`/`PAYMENT_PARTIALLY_REFUNDED`의 대사는 `refundCanceledOrder`를 통해 userId, 양수 금액, 같은 sellerOrderId의 sellerId/amount 일치를 검사한다. `PAYMENT_REFUNDED`에서는 바로 `rePublishExistingRefunds`로 이동하고 이 함수는 sellerOrderId로 기존 환불을 조회·발행할 뿐 입력 내용을 검증하지 않는다. 동일한 잘못된 대사 명령의 처리 결과가 결제 상태에 따라 달라진다.
- **발생 조건:** 정상 취소로 결제가 전액 환불된 뒤, 새 eventId의 대사 payload에서 기존 sellerOrderId를 유지하면서 userId·sellerId·refundAmount 중 하나가 잘못된다. reason이 다른 정상 대사는 기존 계약상 허용되며 이 문제의 발생 조건이 아니다.
- **영향:** 0·음수·기존 금액 불일치·한도 초과·Long 경계·다른 사용자/판매자 명령도 정상 처리로 기록되고 marker와 환불 재발행 Outbox가 커밋된다. 필수 입력 거부 및 실패 시 marker/Outbox 롤백 계약을 위반하고 잘못된 명령을 정상 대사로 숨긴다. 기존 환불 원본을 발행하므로 추가 차감·환불 내용 변조는 재현되지 않았으며, 잘못된 입력과 전액 환불 상태로 영향이 한정되어 Minor로 판단한다.
- **근거:**
  - `PaymentService.kt:95`에서 전액 환불만 `refundCanceledOrder`를 생략한다. `199-210`에는 `validateCancellation`, 양수 검사 또는 기존 sellerId/amount 비교가 없다.
  - 실제 PostgreSQL/Spring 재현에서 정상 대사는 두 상태 모두 원본 사실을 발행했다. 아래의 invalid 명령은 부분 환불에서 모두 `IllegalArgumentException`으로 롤백됐지만 전액 환불에서는 모두 받아들였다.

    | 잘못된 입력 | 부분 환불 | 전액 환불 |
    |---|---|---|
    | 0, -1 | 예외·marker 없음·Outbox 0개 | 정상 반환·marker 저장·Outbox +1개 |
    | 기존 금액 - 1 | 예외·marker 없음·Outbox 0개 | 정상 반환·marker 저장·Outbox +1개 |
    | 20,001, Long.MAX_VALUE, Long.MIN_VALUE | 예외·marker 없음·Outbox 0개 | 정상 반환·marker 저장·Outbox +1개 |
    | 다른 userId, 다른 sellerId | 예외·marker 없음·Outbox 0개 | 정상 반환·marker 저장·Outbox +1개 |

  - 재현의 전액 환불은 실제 서비스에서 20,000원을 한 번 환불해 만든 상태다. 잘못된 금액·식별자의 marker 커밋과 Outbox 추가를 별도 저장소 조회로 확인했으며 원본 payload와 잔액이 그대로임도 단언했다.
  - 기존 PostgreSQL invalid-input 테스트는 RESERVED/COMPLETED 및 RESERVED 대사만 다루고, 전액 환불 대사의 잘못된 입력을 검사하지 않는다. 기존 전액 환불 대사 단위 테스트는 정상 재발행만 확인한다.
- **권장 수정:** 같은 잠금·트랜잭션 안에서 전액 환불 대사에도 결제/사용자, 양수, 기존 환불 sellerId/amount 일치 검증을 적용한다. 이미 환불된 동일 금액의 정상 대사는 잔여액이 0이어도 허용하고 최초 refundId·reason·refundedAt을 그대로 발행한다. 서로 다른 대사 reason을 거부하거나 기존 환불 사실을 바꾸지 않는다. 기존 검증을 재사용하는 최소 수정이 적합하다.
- **필요한 후속 검증:** 실제 PostgreSQL·Spring 프록시에서 부분/전액 환불 대사의 잘못된 userId·sellerId·0/음수·금액 불일치·Long 경계가 거부되고 결제·환불·marker·Outbox가 변하지 않음을 확인한다. 정상 전액 환불 대사는 다른 eventId·reason에도 원본 payload를 재발행하고 신규 환불·금액 효과를 만들지 않아야 한다. 수정으로 영향받는 payment·정산 검증/빌드와 `git diff --check`를 수행하고 RESOLUTION·TASK·계획표·HANDOFF를 갱신한다.
