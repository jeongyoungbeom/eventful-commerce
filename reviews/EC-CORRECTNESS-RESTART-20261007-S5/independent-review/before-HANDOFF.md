# 정산 배치 누락 방지 — 5단계 개발 인계

Task ID: EC-CORRECTNESS-RESTART-20261007-S5
Status: active

대상: `/home/jybeomss/sideProject`
기준 커밋: `96c0e240420ef003e363d7078410dc3a34abc741`
개발일: 2026-10-19 (Asia/Seoul)
이번 역할: workflow-develop
현재 TASK: active / Independent Review: required / 구현·필수 검증 완료

## 현재 상태

S1~S4는 completed다. S5는 배치 설정 수정과 신규 V2 인덱스·통합 테스트를 구현했다. reader는 ID만 (created_at, id) 키로 조회하고 페이지·청크 1000과 DB 체크포인트를 사용한다. writer는 현재 PENDING만 상태·confirmed_at·version을 조건부 UPDATE하여 환불 금액을 덮어쓰지 않는다. 0건 UPDATE는 조회 이후 환불/다른 배치가 먼저 처리한 정상 경쟁으로 허용한다. targetDate는 기존 UTC 자정의 배타적 상한이며 스케줄러는 수정하지 않았다.

## 실제 검증과 근거

`./gradlew :settlement-service:build --offline --no-daemon --console=plain --max-workers=2`가 BUILD SUCCESSFUL in 32s였다. 정산 47개 테스트를 실제 실행했고 실패·오류·생략 모두 0개다. 신규 Batch PostgreSQL 통합 20개·기존 환불 PostgreSQL 통합 19개·소비자 3개·서비스 단위 5개를 포함한다.

신규 검증은 0/1/999/1000/1001/2000건과 동일 시각 ID 정렬·UTC 경계·대상 외 상태·동일 날짜 재실행, 첫/중간 청크 갱신 후 롤백과 같은 JobInstance 재시작, 조회 뒤 부분/전액 환불, 실제 Lock 대기를 관측한 환불 선행/확정 선행 각 3회, 두 준비된 JobExecution의 동시 행 갱신·오래된 저장 version 거부다. Batch 메타데이터도 실제 PostgreSQL에 저장·재조회했다.

수정 전 2000건 회귀는 OptimisticLockException으로 FAILED였다. 초기 중복 Bean 설정 오류는 test-setup-attempt-1.log, 초기 통합 19/20 통과·동시 launch barrier timeout은 batch-integration-attempt-1.log와 test-results-attempt-1/에 따로 보존했다. 최종 두 실행 경합 테스트는 JobExecution을 순차 생성한 다음 제품 Job을 병렬 실행한다.

근거: `.workflow/results/EC-CORRECTNESS-RESTART-20261007-S5/DEVELOPMENT.md`, `build.log`, `validation.json`, `source-manifest.json`, `test-results/`. 시작 workflow 문서와 소스 hash는 before-*.md·baseline.json이다. 정산 배치 설정 외 기존 소스 339개 hash가 유지됐고 신규 파일 2개·삭제 0개다. S1~S4의 미커밋 제품 변경과 근거를 보존했다. git diff --check는 통과했고 실행 중 테스트 컨테이너는 없다.

## 검증 한계와 다음 작업

실제 OS/JVM 종료 대신 새 JobExecution·StepScope reader와 DB 메타데이터로 재시작했다. 두 JobLauncher.run 동시 시작 성공은 검증하지 않았다. 실제 Kafka 전달·DLT·외부 PG는 변경 범위 밖이다. 동일 성공 코드의 테스트·빌드는 재실행할 필요가 없다.

다음 별도 세션은 workflow-independent-review다. TASK·계획의 S5 범위와 현재 소스 hash·실제 검증 근거를 대조해 인수하고 이 HANDOFF를 inactive로 바꾼다. 신규 리뷰 경로는 `reviews/EC-CORRECTNESS-RESTART-20261007-S5/REVIEW.md`다. 리뷰 및 필요한 반영 전에는 TASK를 completed로 바꾸거나 6단계로 넘어가지 않는다. 커밋·배포·서비스 DB 초기화·ARL 복원은 수행하지 않았다.
