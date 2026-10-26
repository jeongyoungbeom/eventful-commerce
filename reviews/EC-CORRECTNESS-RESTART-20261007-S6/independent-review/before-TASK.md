# 웹훅 검증 경계 보완 — 6단계

Task ID: EC-CORRECTNESS-RESTART-20261007-S6
Status: active
Independent Review: required

## 목표와 범위

세션별 계획의 6단계만 구현한다. 대상은 /home/jybeomss/sideProject이며 D-001~D-004를 적용한다. S1~S5 완료 자료·기존 미커밋 변경을 보존한다. 실제 PG 연동·호출, ARL, 기존 데이터 이전·서비스 DB 초기화·배포는 제외한다.

## 설계

실제 POST /payments/webhook은 원문 body와 HTTP headers를 PG별 PaymentWebhookVerifier에 전달한다. 검증자는 발신자와 전체 결제 결과를 검증한 뒤 내부 요청을 반환한다. PG 규격이 없으므로 실제 구현을 만들지 않고 미구성은 503으로 기본 차단한다. 인증 실패는 401이며 임의 서명 규격을 실제 PG 인증으로 사용하지 않는다.

로컬 POST /payments/simulation/webhook은 명시적 payment-simulation 프로필·enabled=true·전용 비밀 토큰을 요구하고 prod에서는 생성하지 않는다. Gateway에서는 일반 access JWT도 요구한다. 기본 dev/docker 프로필은 시뮬레이션을 활성화하지 않는다. 기존 시나리오는 이 별도 경로·토큰·JWT로 변경한다.

서비스는 SUCCESS/FAILED만 허용하고 금액 필수·양수·예약 금액 일치, 거래 ID 필수·공백/길이 제한을 검사한다. 같은 Payment 행 잠금 아래 최초 거래 ID를 저장하며 다른 거래 ID로 같은 결제를 바꿀 수 없다. 전역 UNIQUE와 조회 검사로 다른 결제의 거래 ID 재사용을 거부한다. 검증은 종결 상태의 중복 반환보다 먼저 수행한다. 같은 거래의 상반된 허용 결과는 S2의 최초 종결 결과 유지 정책을 따른다. 거래 ID·결제 상태·환불·Outbox는 같은 DB 트랜잭션이다.

## 완료 조건

1. 금액 불일치·누락·0/음수, 거래 ID 누락·잘못된 형식·동일 결제 불일치·다른 결제 재사용, 미허용 result를 거부하며 결제·환불·Outbox를 보존한다.
2. 실제 수신의 검증자 미설정·인증 없음·잘못된 인증을 차단한다. 원문과 headers 전달·검증 후 정상 처리 및 예외 시 무변경을 확인한다.
3. 기본 dev/docker/prod에서 시뮬레이션을 비활성화하고, 명시적 로컬 프로필의 토큰 없음·잘못된 토큰을 차단하며 정상 경로를 검증한다. Gateway의 정확한 POST 실제 웹훅 예외와 시뮬레이션 JWT 보호를 확인한다.
4. 실제 PostgreSQL에서 거래 ID UNIQUE 경합·중복·불일치·거래 ID/상태/Outbox 동반 롤백을 검증한다. 기존 결제 동시성·환불 테스트도 통과해야 한다.
5. 결제·Gateway 서비스 build 및 직접 영향받는 테스트와 변경 스크립트 구문 검증을 완료하고 로그·XML·소스 hash를 남긴다.
6. 구현 후 별도 세션의 독립 리뷰 및 필요한 반영 전에는 TASK를 completed로 바꾸지 않는다.

## 진행 상태

2026-10-19 6단계 구현·필수 검증을 마쳤다. 실제 PG의 검증자 미구성 503 차단과 원문/headers 검증 계약, 프로필·토큰·JWT로 보호한 별도 로컬 시뮬레이션, 금액·허용 결과 검증 및 거래 ID 저장·UNIQUE·동반 롤백을 구현했다. 기본 dev/docker 및 prod에서는 시뮬레이션을 활성화하지 않는다. 기존 세 시나리오 호출을 보호된 경로로 맞추고 계약 문서와 선택 로컬 compose 설정을 추가했다.

결제·Gateway build 및 Payment 엔티티를 사용하는 정산 환불 통합 테스트는 BUILD SUCCESSFUL in 36s다. 결제 83개(실제 PostgreSQL 42개·HTTP/프로필 11개 포함), Gateway 6개, 정산 환불 PostgreSQL 19개 등 108개를 실제 실행했고 실패·오류·생략 모두 0개다. 스크립트 4개 구문·로컬 HTTP helper·선택 compose 설정 검증 및 git diff --check가 통과했다. 완료 조건 1~5를 충족했다.

근거는 `.workflow/results/EC-CORRECTNESS-RESTART-20261007-S6/`의 DEVELOPMENT.md·build.log·validation.json·test-results/·source-manifest.json에 있다. 실제 PG 검증자는 구성하지 않았고 실제 수신은 503 기본 차단이다. 전체 compose·실제 Kafka·외부 PG 호출·OS/JVM 재기동은 실행하지 않았다. S1~S5 자료와 기존 미커밋 변경을 보존했다.

완료 조건 6은 별도 독립 리뷰 대기다. TASK는 active, 일치하는 HANDOFF는 active로 유지한다. 다음 workflow는 workflow-independent-review이며 이번 개발 세션에서는 completed로 바꾸지 않는다.
