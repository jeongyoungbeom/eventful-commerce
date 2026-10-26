# 정산 배치 누락 방지 — 5단계

Task ID: EC-CORRECTNESS-RESTART-20261007-S5
Status: active
Independent Review: required

## 목표와 범위

세션별 계획의 5단계만 구현한다. 대상은 /home/jybeomss/sideProject이며 D-001~D-004를 적용한다. S1~S4의 완료 기록·검증 근거와 미커밋 변경을 보존한다. 6단계·ARL·기존 데이터 이전·배포는 제외한다.

## 설계

PENDING 집합 축소와 무관한 JdbcPagingItemReader로 created_at·id 오름차순 키 조회를 수행한다. 페이지와 청크 크기는 1000으로 같게 유지하고 커밋한 마지막 키를 Spring Batch DB ExecutionContext에 보존한다. targetDate는 기존과 같은 UTC 자정의 배타적 상한이며 과거 미처리 대상도 포함한다. 엔티티 snapshot 대신 ID만 읽고 writer는 현재 PENDING 행의 상태·확정 시각·version만 조건부 갱신한다. UPDATE 행 잠금과 상태 재검사로 환불이 먼저면 확정을 건너뛰고 확정이 먼저면 환불이 최신 행을 차감하도록 한다. 금액 컬럼은 배치가 쓰지 않는다. 조회용 복합 인덱스를 정의한다.

## 완료 조건

1. 실제 PostgreSQL·Spring Batch Job에서 0/1/999/1000/1001/2000건을 누락 없이 처리하고 같은 생성 시각의 ID 순서도 안정적이어야 한다.
2. UTC 기간 경계와 대상 외 상태를 보존하고 동일 날짜 새 실행에도 중복 효과·금액 변경이 없어야 한다.
3. 중간 청크 실패 시 해당 청크·체크포인트를 함께 롤백하고, 같은 JobInstance 재시작으로 남은 대상을 처리한다.
4. 실제 DB에서 배치 확정·환불의 양쪽 잠금 순서와 조회 후 환불을 검증한다. 환불 상태·금액·처리 기록을 덮어쓰지 않는다.
5. 직접 영향받는 정산 테스트와 서비스 build를 통과하고 실제 검증 근거를 남긴다.
6. 구현 후 별도 세션 독립 리뷰 및 필요한 반영 전에는 TASK를 completed로 바꾸지 않는다.

## 진행 상태

2026-10-19 구현·필수 검증을 마쳤다. JdbcPagingItemReader의 (created_at, id) 키 조회, PENDING 조건부 상태·확정 시각·version UPDATE, 복합 조회 인덱스를 구현했다. 정산 서비스 build는 BUILD SUCCESSFUL in 32s였고 47개 테스트를 실제 실행해 실패·오류·생략 모두 0개다. 신규 Batch PostgreSQL 통합 20개와 기존 환불 PostgreSQL 통합 19개를 포함한다. 완료 조건 1~5를 개발 검증에서 확인했다.

0/1/999/1000/1001/2000건·동일 시각 ID 정렬·UTC 기간 경계·재실행, 첫/중간 청크 실패·DB 체크포인트 동반 롤백·같은 JobInstance 재시작, 조회 뒤 부분/전액 환불, 환불/확정 양쪽 실제 Lock 대기 각 3회, 사전 생성 두 JobExecution의 행 경합·version 방어를 검증했다. OS 프로세스 재기동과 두 JobLauncher.run 동시 시작 성공은 검증하지 않았다. 수정 전 2000건 회귀는 OptimisticLockException으로 FAILED였고 로그·XML을 보존했다.

변경은 배치 설정 1개와 신규 migration·통합 테스트 2개다. 기존 소스 339개 hash가 보존됐고 삭제는 없다. git diff --check는 통과했다. 근거는 `.workflow/results/EC-CORRECTNESS-RESTART-20261007-S5/`에 있다. TASK는 active·Independent Review: required를 유지하고 일치하는 HANDOFF를 active로 남긴다. 완료 조건 6은 다음 별도 workflow-independent-review 세션에서 진행한다. 6단계·커밋·배포·서비스 DB 초기화·ARL 복원은 수행하지 않았다.
