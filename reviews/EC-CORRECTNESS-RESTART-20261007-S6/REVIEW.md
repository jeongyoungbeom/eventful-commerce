# 6단계 웹훅 검증 독립 리뷰

Task ID: EC-CORRECTNESS-RESTART-20261007-S6
검토일: 2026-10-19 (Asia/Seoul)
수행 역할: workflow-independent-review
판정: finding 0개 / 6단계 completed / 계획된 1~6단계 완료

## 검토 범위

S6에서 변경한 PaymentWebhookController·PaymentSimulationController·PaymentWebhookVerifier·PaymentWebhookRequest·PaymentWebhookService, Payment 엔티티·저장소·V4 스키마·예외 처리, 시뮬레이션 프로필, Gateway의 SecurityConfig·GatewayJwtFilter와 테스트 의존성, HTTP·서비스·PostgreSQL·Gateway 테스트를 검토했다. README·웹훅 계약·선택 compose·공통 helper와 세 시나리오 호출을 대조했다. 직접 영향받는 PaymentService의 대기 환불·취소·대사, OrderEventsConsumer와 정산 환불 테스트도 확인했다. 제품 코드·테스트는 수정하지 않았다.

## 요구사항 출처

사용자의 `/home/jybeomss/sideProject` 개발 계속 요청, AGENTS.md, active S6 TASK의 완료 조건 1~6, 세션별 계획의 6단계와 한 세션 한 workflow 역할 규칙을 적용했다. 현재 구현·테스트·실행 근거를 독립 검토하고 global D-001~D-004 및 S6 D-010, 같은 Task ID의 active 개발 HANDOFF와 대조해 인수했다. 적용한 스킬은 [workflow-independent-review SKILL.md](/home/jybeomss/sideProject/.agents/skills/workflow-independent-review/SKILL.md)다.

## 수행한 검증

- 현재 소스·설정·문서 384개의 SHA-256이 개발 source-manifest.json과 모두 일치했다. 개발 artifact-manifest.json의 40개 파일도 리뷰 시작 시 전부 일치했다. 상태 갱신 전 workflow 문서와 개발 manifest를 independent-review/에 보존했다. 시작 baseline과 최종 manifest의 차이, git diff 및 직접 호출 경로를 대조했다. 기존 docs/failure-scenarios.md·docs/schema-migration-policy.md는 최종 manifest의 수집 범위에 추가된 기존 문서이며 S6 신규 구현으로 계산하지 않았다.
- 최종 build.log의 `BUILD SUCCESSFUL in 36s`와 최종 XML 8개를 읽어 테스트 108개, 실패·오류·생략 모두 0개를 확인했다. 결제 83개(실제 PostgreSQL 42개·HTTP/프로필 11개 포함), Gateway 6개, 직접 영향받는 정산 환불 PostgreSQL 19개다. 중간 실패 로그·XML과 최종 결과를 구분했다.
- 같은 코드에서 이미 통과한 검증을 반복하지 않는 AGENTS.md에 따라 테스트·빌드는 재실행하지 않았다. 위 108개는 개발 세션의 실제 실행 결과다. 이번 리뷰의 `git diff --check`는 통과했다.
- scripts-validation.log의 변경 스크립트 4개 bash -n, 로컬 HTTP stub의 경로·JWT·전용 토큰·본문 전달 및 토큰 비출력, 토큰 미구성 사전 차단, 선택 compose 구문·빈 토큰 차단 결과를 읽고 해당 스크립트와 설정을 대조했다. 실제 시나리오를 재실행하지 않았다.

| 확인한 실패 조건 | 코드와 기존 실행 근거 대조 |
|---|---|
| 검증되지 않은 요청이 실제 결제를 확정함 | 실제 controller는 원문 bytes·HTTP headers를 검증자에게 넘기며 검증자 미구성은 503이다. 검증자 반환 뒤에만 서비스에 진입한다. 테스트 전용 검증자를 제품 bean으로 등록하지 않았다. 미구성·인증 누락/실패 차단, 정확한 원문·headers 전달, HTTP→실제 서비스 프록시→PostgreSQL 정상/거절 검증을 확인했다. |
| 기본 또는 prod 설정에서 로컬 우회 경로가 열림 | controller는 payment-simulation & !prod 프로필과 enabled=true를 모두 요구한다. 기본 dev/docker는 생성하지 않으며 prod와 시뮬레이션 동시 활성도 거부한다. 미구성 토큰은 503, 누락/불일치는 401이며 비교는 MessageDigest.isEqual이다. ConfigData 프로필 테스트와 실제 HTTP 검증 근거가 있다. |
| Gateway가 시뮬레이션이나 실제 경로 하위를 JWT 없이 전달함 | 필터와 SecurityFilterChain 모두 정확한 POST /api/payments/webhook만 예외로 둔다. 하위 경로·다른 method·시뮬레이션에는 access JWT가 필요하다. 실제 보안 체인 테스트에서 토큰 없음·전용 토큰만 있음·refresh JWT를 거부하고 access JWT를 허용한다. |
| 잘못된 금액·결과·거래 ID가 결제를 변경함 | result는 SUCCESS/FAILED, amount는 필수 양수·원금 일치, pgTxId는 필수 1~200자·공백/제어문자 제외다. 같은 Payment 행 잠금 아래 저장된 거래 ID도 대조한다. 종결 상태의 조기 반환 전에 검증한다. 예약/종결 상태의 잘못된 입력과 DB 상태·version·Outbox 보존 테스트를 확인했다. |
| 서로 다른 결제가 같은 거래 ID를 동시에 채택함 | 사전 조회와 DB 전역 UNIQUE를 함께 사용한다. 실제 PostgreSQL에서 두 요청 모두 사전 조회를 통과하도록 barrier를 둔 경합을 3회 실행한 근거가 있다. 하나만 커밋하고 패자의 거래 ID·상태·version·Outbox는 롤백되며 새 거래 ID로 재시도한다. 실제 V4 SQL과 JPA UNIQUE 정의도 대조했다. |
| 웹훅 실패 뒤 거래 ID·결제·환불·Outbox 일부만 남음 | handle과 대기 환불은 같은 REQUIRED 트랜잭션이며 rollbackFor=Exception을 적용한다. flush 뒤 실패 및 환불 JSON checked 예외에서 거래 ID·결제·대기 집합·환불·Outbox가 함께 롤백하는 검증이 있다. 기존 취소·대사 경합과 정산 환불 연계 테스트도 최종 결과에 포함된다. |
| 기존 로컬 시나리오가 실제 PG 경로를 계속 사용함 | 공통 simulate_payment helper가 별도 경로에 사용자 JWT와 전용 토큰을 모두 전달한다. E2E·멱등성·Saga 세 호출과 README 예시가 이를 사용하며 토큰 미설정은 네트워크 호출 전에 차단한다. 기본 compose는 보존하고 선택 override에서만 로컬 프로필을 켠다. |

S2의 같은 거래에 대한 최초 종결 결과 유지 정책과 D-010의 PG별 namespace 정규화 책임을 유지한다. 완료 조건 1~5의 개발 검증 근거를 실제 코드·테스트·XML과 대조했고, 이번 별도 독립 리뷰로 완료 조건 6을 충족했다.

## 검토 제외 범위

특정 실제 PG 검증자 구현·공식 인증 규격 선정·실제 PG 호출, ARL 복원, 과거 DB 자료 이전·서비스 DB 초기화, 운영 정책 변경·배포·커밋은 제외했다. S1~S5 자료와 기존 미커밋 변경을 보존했다. S6 밖 기존 구현 전체를 다시 리뷰한 것은 아니다.

## 미검증 항목

이번 리뷰에서 새 테스트·빌드·전체 compose·실제 Kafka·외부 PG·OS/JVM 재기동을 실행하지 않았다. HTTP 근거는 MockMvc이며 실제 네트워크 전체 시나리오 성공으로 해석하지 않는다. PG 테스트 검증자는 계약 검증용이고 실제 PG 인증 구현이 아니다. 실제 PG 검증자는 미구성 상태이며 실제 수신은 503 기본 차단이다. 이 한계는 현재 TASK의 완료 조건과 범위에 부합한다.

## Finding

없음. 검토 범위에서 재현 가능하거나 코드 흐름으로 입증되는 수정 필요 결함을 발견하지 않았다.

## 종료와 다음 작업

S6 TASK를 completed, 인수한 HANDOFF를 inactive로 바꾸고 계획표를 1~6단계 completed로 갱신한다. finding이 없으므로 RESOLUTION 및 workflow-apply-review는 필요 없다. 스킬의 “finding이 0개이고 … TASK를 `completed`로 바꾸고 종료한다” 규칙에 따라 이번 세션은 독립 리뷰로 종료한다. 현재 계획에 남은 단계는 없다. 실제 PG 연동 등 추가 개발은 새로운 요구사항·범위로 정해야 한다.
