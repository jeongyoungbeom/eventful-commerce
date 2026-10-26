# 6단계 웹훅 검증 구현·검증 완료

Task ID: EC-CORRECTNESS-RESTART-20261007-S6
Status: active

대상: /home/jybeomss/sideProject
기준 커밋: 96c0e240420ef003e363d7078410dc3a34abc741
작성일: 2026-10-19 (Asia/Seoul)
이번 역할: workflow-develop
현재 TASK: active / Independent Review: required / 독립 리뷰 대기

## 실제 구현과 검증

실제 /payments/webhook은 원문 body·headers를 PG별 PaymentWebhookVerifier에 전달하며, 미구성은 503으로 기본 차단한다. 실제 PG 구현·임의 서명을 추가하지 않았다. 별도 /payments/simulation/webhook은 payment-simulation 프로필·enabled·전용 토큰을 요구하고 prod에서는 비활성이다. Gateway는 시뮬레이션에 access JWT도 요구하며 정확한 POST 실제 웹훅만 예외다.

서비스는 금액 필수·양수·일치, SUCCESS/FAILED, 거래 ID 필수·형식·기록과 일치·타 결제 재사용을 검사한다. Payment.pgTxId와 전역 UNIQUE·새 스키마 SQL을 추가했다. 상태·거래·환불·Outbox가 같은 트랜잭션이며 기존 행 잠금·version을 유지한다. 같은 거래의 늦은 상반된 허용 결과는 S2의 최초 종결 유지 정책을 따른다.

기존 세 시나리오를 보호된 로컬 helper로 맞추고 README·docs/payment-webhook-contract.md 및 명시적 선택 compose override를 추가했다. 기본 compose는 변경하지 않았다.

최종 build.log: BUILD SUCCESSFUL in 36s. 결제 83개(실제 PostgreSQL 42개·HTTP/프로필 11개 포함), Gateway 6개, Payment 엔티티 연계 정산 환불 PostgreSQL 19개 등 108개 실제 실행·실패/오류/생략 0개다. 거래 ID UNIQUE 경합 3회, 결제·거래·환불·Outbox 동반 롤백, HTTP→실제 서비스 프록시→DB, 기본/dev/docker/prod의 시뮬레이션 차단과 로컬 인증, 실제 SecurityFilterChain 보호를 검증했다. 스크립트 4개 bash -n·로컬 HTTP helper·선택 compose 구문 및 토큰 미설정 차단, git diff --check가 통과했다. 동일 성공 코드 테스트를 반복하지 않는다.

근거: `.workflow/results/EC-CORRECTNESS-RESTART-20261007-S6/`의 DEVELOPMENT.md·validation.json·build.log·scripts-validation.log·source-manifest.json·artifact-manifest.json·test-results/ 및 시작 baseline/이전 문서. 중간 검증 실패도 별도 보존했다.

## 남은 작업과 한계

다음 별도 workflow-independent-review에서 S6 소스·계약·검증 자료를 실제 상태와 대조한다. finding이 있으면 이후 별도 workflow-apply-review로 넘기고 없으면 completed로 전환한다. 이번 TASK는 구현 검증만 완료했고 독립 리뷰는 수행하지 않았다. 인수 시 HANDOFF를 inactive로 전환한다.

특정 실제 PG 검증자·외부 호출은 범위 밖이며 실제 수신은 현재 503 차단이다. 테스트 검증자를 실제 PG 인증으로 간주하지 않는다. 전체 compose·실제 Kafka 브로커·OS/JVM 재기동은 실행하지 않았다. 기존 서비스 DB 초기화·자료 이전·배포·커밋·ARL 복원은 수행하지 않았다. S1~S5 자료·기존 미커밋 변경을 보존했으며 주문·배송·정산·공통 모듈 기존 파일 207개 hash가 일치한다.
