# 5단계 정산 배치 독립 리뷰

Task ID: EC-CORRECTNESS-RESTART-20261007-S5
검토일: 2026-10-19 (Asia/Seoul)
수행 역할: workflow-independent-review
판정: finding 0개 / 5단계 completed

## 검토 범위

S5 개발 시작 기준선 대비 수정한 SettlementBatchConfig.kt, 신규 V2__settlement_confirmation_index.sql 및 SettlementBatchPostgresIntegrationTest.kt를 검토했다. 직접 영향받는 Settlement·SettlementRepository·SettlementService.applyRefund·SettlementPayExecutor·PaymentEventsConsumer·SettlementScheduler, 서비스 설정과 빌드 의존성을 대조했다. 검토 중 제품 코드와 테스트는 수정하지 않았다.

## 요구사항 출처

사용자의 `/home/jybeomss/sideProject` 개발 계속 요청, 저장소 AGENTS.md, active S5 TASK의 완료 조건 1~6, COMMERCE_REMEDIATION_PLAN.md의 5단계와 세션 진행 규칙을 적용했다. 실제 구현·테스트로 독립 검토한 뒤 global D-001~D-004 및 S5 D-009, 같은 Task ID의 active 개발 HANDOFF를 대조해 인수했다. 리뷰 스킬은 `/home/jybeomss/sideProject/.agents/skills/workflow-independent-review/SKILL.md`다.

## 수행한 검증

- 현재 소스·설정 342개 SHA-256이 S5 개발 검증 source-manifest.json과 모두 일치한다. 개발 시작 기준선의 340개 중 배치 설정 1개만 변경됐고 기존 339개는 유지됐으며 신규 2개·삭제 0개다. 개발 artifact-manifest.json의 22개 파일도 리뷰 시작 시 모두 일치했다. workflow 변경 전 문서와 이 manifest를 independent-review/에 보존했다.
- 보존된 build.log의 `BUILD SUCCESSFUL in 32s`와 최종 XML 4개를 직접 읽었다. 정산 47개(신규 Batch PostgreSQL 통합 20개, 기존 환불 PostgreSQL 통합 19개, 소비자 3개, 서비스 단위 5개)가 실제 실행됐고 실패·오류·생략은 모두 0개다. 초기 실패 로그와 최종 성공 XML을 구분했다.
- 동일 코드의 성공 검증을 반복하지 않는 AGENTS.md에 따라 테스트·빌드는 이번 리뷰에서 재실행하지 않았다. 위 47개는 개발 세션의 실행 결과다. `git diff --check`는 이번 리뷰에서 실행해 통과했다.
- 실제 사용 버전인 Spring Batch 5.1.2와 Spring Boot 3.3.5의 로컬 sources.jar 원본도 읽었다. JdbcPagingItemReader의 start.after 저장·복원, PostgresPagingQueryProvider의 다음 키 조건, TaskletStep의 writer·ExecutionContext·StepExecution 갱신 및 Boot Batch의 DataSource·transactionManager 선택을 확인했다. 제품 설정의 같은 DB 트랜잭션 사용과 통합 검증 결과가 일치한다.

| 확인한 실패 조건 | 코드 및 실행 근거 대조 |
|---|---|
| PENDING 집합 축소로 다음 페이지가 누락됨 | reader는 ID와 created_at만 읽고 (created_at, id) ASC의 다음 키 조건으로 조회한다. status 조건이 줄어도 OFFSET을 늘리지 않는다. 0/1/999/1000/1001/2000건, 동일 시각·역순 ID 입력에서 전체 확정·안정적 순서를 확인한 테스트가 통과했다. |
| 기간 밖 행이나 다른 상태가 변경됨 | targetDate를 기존 UTC 자정 배타적 상한으로 변환한다. 오래된 PENDING을 포함하고 경계 동일/직후 및 CONFIRMED·PAID·환불 상태는 보존하는 검증을 확인했다. 인덱스는 status·created_at·id 순서다. |
| 같은 날짜 재실행으로 확정 효과가 반복됨 | writer도 현재 PENDING 조건을 재검사한다. 새 JobInstance 실행 뒤 데이터 전체 일치·읽기 0건과 완료 JobInstance 재요청 거부를 검증했다. 사전 생성된 두 JobExecution의 병렬 실행도 모든 행 version 1과 금액 보존을 확인했다. |
| 청크 실패 뒤 일부 갱신이나 잘못된 체크포인트가 남음 | 페이지·청크 크기가 모두 1000이며 saveState가 활성화됐다. 같은 DataSource·트랜잭션으로 청크 갱신과 체크포인트를 처리한다. 첫/두 번째 청크 writer 후 예외에서 미커밋 상태·version과 DB 체크포인트 보존, 같은 JobInstance의 새 JobExecution 재시작, 남은 첫 ID와 최종 2000건을 검증했다. |
| 배치가 환불 금액·상태를 덮어씀 | writer는 status·confirmed_at·version만 UPDATE하고 금액은 쓰지 않는다. 환불이 먼저 끝나면 현재 상태 조건으로 0건 갱신하며, 확정이 먼저 잠그면 환불이 커밋 후 최신 행을 읽는다. 조회 뒤 부분/전액 환불과 양쪽 실제 Lock 대기 각 3회의 상태·금액·version·receipt 보존 검증이 있다. |
| JDBC 확정 후 오래된 JPA 저장이 성공함 | 조건부 UPDATE가 version도 증가시킨다. 확정 전 조회한 엔티티의 늦은 저장에서 OptimisticLockingFailureException과 최종 금액·상태·환불 기록 보존을 확인했다. |

완료 조건 1~5의 개발 검증 근거를 실제 코드·테스트·XML과 대조했다. 이번 별도 독립 리뷰로 완료 조건 6을 충족했다.

## 검토 제외 범위

6단계 웹훅 검증, 기존 환불 상태·지급 정책 변경, 스케줄러 날짜 산정 변경, 과거 DB 자료 이전, ARL, 배포는 제외했다. S1~S4 자료와 미커밋 변경을 보존했다. S5 밖 기존 구현 전체의 결함 부재를 보증하는 리뷰는 아니다.

## 미검증 항목

이번 리뷰에서 새 테스트·빌드는 실행하지 않았다. 개발 검증은 OS/JVM 프로세스 종료·재기동 대신 동일 JobInstance의 새 JobExecution·StepScope reader와 DB 메타데이터를 사용한다. 두 JobLauncher.run의 동시 시작 성공은 미검증이며, 최종 병렬 테스트는 JobExecution을 순차 생성한 다음 제품 Job 두 개를 병렬 실행한다. 실제 Kafka·DLT·외부 PG는 범위 밖이며 실행하지 않았다. 이 한계는 TASK의 필수 검증 충족 판단을 바꾸지 않는다.

## Finding

없음. 검토 범위에서 재현 가능하거나 코드 흐름으로 입증되는 수정 필요 결함을 발견하지 않았다.

## 종료와 다음 작업

S5 TASK를 completed, 인수한 HANDOFF를 inactive로 바꾸고 계획표를 갱신한다. finding이 없으므로 RESOLUTION 및 workflow-apply-review는 필요 없다. 리뷰 스킬의 “finding이 0개이고 … TASK를 `completed`로 바꾸고 종료한다”와 AGENTS.md의 한 세션 한 workflow 역할 규칙에 따라 이 세션은 독립 리뷰로 종료한다. 다음 별도 workflow-develop 세션은 6단계 웹훅 검증 경계를 진행한다. 6단계는 미착수다.
