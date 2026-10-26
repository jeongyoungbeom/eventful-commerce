# 3단계 독립 리뷰 — 보상 롤백 후 복구 결함 1건

Task ID: EC-CORRECTNESS-RESTART-20261007-S3
검토일: 2026-10-19 (Asia/Seoul)
역할: workflow-independent-review
기준 커밋: 96c0e240420ef003e363d7078410dc3a34abc741
판정: Major 1건. TASK는 active로 유지하고 별도 workflow-apply-review 세션에 넘긴다.

## 검토 범위

3단계의 주문 제품 코드·빌드 6개, 기존 단위 테스트 2개와 새 OrderReservationPostgresIntegrationTest를 검토했다. 부분 취소 후 남은 판매자 주문 확정, 만료 시점 재검사, 주문 행 잠금, Redis 재고 확정·해제·재입고, 실패 보상, DB/Outbox/eventId marker 롤백과 재시도를 대조했다. 직접 영향받는 Orders/SellerOrder/OrderItem, OrderCancelService, OrderSagaService, IdempotencyHandler, OutboxEventService, 실제 배송 소비자·ShippingService 및 기존 재고 정리·대사 호출 흐름도 확인했다.

## 요구사항 출처

- 사용자의 `/home/jybeomss/sideProject 개발 이어서 진행해.` 요청
- AGENTS.md, .agents/skills/workflow-independent-review/SKILL.md
- active .workflow/TASK.md의 S3 목표와 완료 조건 1~6
- .workflow/COMMERCE_REMEDIATION_PLAN.md의 3단계와 세션 진행 규칙
- 적용되는 active 결정 D-001~D-004, D-007과 같은 Task ID의 개발 인계. 구현 설명은 실제 코드·검증 근거와 대조했다.

## 수행한 검증

1. 개발 종료 source-manifest.json의 기존 파일 395개가 현재 파일 hash와 전부 일치했다. 보존된 JUnit XML도 직접 집계해 order-service 68개, 실패·오류·생략 0개와 shipping-service 4개, 실패·오류·생략 0개를 확인했다. 주문 68개는 개발 세션에서 실제 실행됐고 배송 4개는 당시 UP-TO-DATE 결과다. build.log의 BUILD SUCCESSFUL in 35s와 모듈별 실제 실행 여부를 확인했다. 동일 소스이므로 전체 테스트·빌드는 이번 세션에서 반복하지 않았다.
2. 기존 테스트가 부분 취소 전후·중복·만료 선후행·실제 PostgreSQL Lock 대기·각 3회 경합·확정 payload 직렬화 롤백 후 즉시 재확정·실제 배송 수신자 연계를 검증하는지 코드와 XML을 대조했다.
3. 기존 테스트에 없는 **재고 일부 확정 → 보상 재고 복원 → 보상 이벤트 기록 실패 → DB 롤백 → 주입 실패 제거 후 복구**를 진단했다. 제품·테스트 소스를 변경하지 않고 기존 컴파일 결과와 fixture, 실제 Spring 서비스 프록시·저장소, 격리 postgres:16-alpine·redis:7.2-alpine을 사용했다. 재현 프로그램은 ReviewRecoveryProbe.java다. 주입 실패 제거 후 동일 eventId 재시도 2회·만료·사용자 취소 모두 TERMINAL_CONFLICT로 실패했다. 재현 프로세스 exit code 0은 진단 완료를 뜻하며 제품 동작 통과를 뜻하지 않는다.
4. git diff --check 통과. 제품·테스트·기존 S1/S2 리뷰는 변경하지 않았고, 실행한 임시 PostgreSQL/Redis 컨테이너는 종료했다. 후속 문서 갱신 후 보존 검사를 review-validation.json에 남겼다.

재현·검증 근거: .workflow/results/EC-CORRECTNESS-RESTART-20261007-S3/independent-review/의 ReviewRecoveryProbe.java, run-review-probe.py, review-runtime.gradle, runtime.log, recovery-probe.log, recovery-probe-summary.log, review-baseline.json, review-validation.json. 같은 S3 결과 디렉터리 상위의 개발 source-manifest.json·build.log·test-results도 참조했다. 진단 스크립트는 리뷰 증거이며 제품·정상 회귀 테스트나 ARL 하네스에 연결하지 않았다.

## 검토 제외 범위

S1·S2의 완료된 결제·정산 변경을 재리뷰하지 않았다. 4단계 배송 상태별 취소 허용 정책, 5단계 정산 페이징, 6단계 웹훅 검증, 기존 데이터 이전, ARL·실험 코드 복원과 배포는 제외했다.

## 미검증 항목

실제 Kafka 브로커 전달·DLT 격리·외부 PG 호출은 실행하지 않았다. 실제 배송 소비자 연계는 기존 개발 통합 검증의 증거와 코드를 확인했으며 비동기 배송 완료 worker는 해당 검증에서 mock이다. 신규 진단은 기존 테스트 전체를 재실행한 결과가 아니다. 운영·기존 개발 DB는 사용하지 않았다.

## REV-001 — 보상 재고 복원 뒤 DB 롤백이 발생하면 주문을 재처리할 수 없다

- 심각도: Major
- 위치: order-service/src/main/kotlin/com/eventfulcommerce/order/service/OrdersService.kt:261~265의 보상 호출, 같은 파일 247~259의 재확정 루프. 직접 실패 지점은 OrderCancelExecutor.kt:89~103의 DB 품목 상태에 따른 release 호출이다. release.lua:57~58은 RESTOCKED를 TERMINAL_CONFLICT로 반환한다.
- 문제: 일부 예약을 Redis에서 COMMITTED로 바꾼 다음 다른 품목 확정이 실패하면 보상은 앞 품목을 RESTOCKED, 나머지를 RELEASED로 만든다. 이후 취소 payload 직렬화나 DB/Outbox 기록이 실패하면 DB의 품목·판매자 상태, 보상 사가와 eventId marker는 롤백되지만 Redis의 복원 상태는 남는다. 다음 결제 재시도는 DB의 RESERVED를 보고 이미 복원된 예약을 다시 commit하려 하고, 다시 보상할 때도 DB의 RESERVED를 보고 RESTOCKED 예약에 release를 호출한다. release는 이 상태를 거절하므로 실패 원인이 사라져도 처리가 수렴하지 않는다.
- 발생 조건: 판매자 A가 이미 취소된 3판매자 주문에서 B/C 중 첫 Redis commit만 성공하도록 만들고, 이어지는 보상 취소 payload 기록에 JsonProcessingException을 주입했다. 이는 기존 통합 검증에서 사용하는 재고 실패·checked 직렬화 실패를 보상 경로에 결합한 것이다. 최초 롤백 이후 주입을 모두 해제하고 같은 eventId 재시도 2회, 만료, 사용자 전체 취소를 순서대로 호출했다.
- 영향: 기존 A의 취소는 보존되지만 B/C는 DB에 RESERVED, Redis에 RESTOCKED/RELEASED로 남는다. 신규 ORDER_CANCELED·환불 요청과 성공 marker가 생성되지 않아 실제 결제된 잔여 판매자의 환불도 진행되지 않는다. 자동 결제 재시도뿐 아니라 만료와 사용자 취소도 실패한다. 일반 재시도로 해소되지 않는 상태이므로 완료 조건 4의 실패 후 재시도 가능을 충족하지 못한다.
- 근거: recovery-probe-summary.log에서 최초와 최종 상태가 모두 ORDER_PARTIALLY_CANCELED, marker=false, outbox=2다. 2개 Outbox는 실패 전에 완료된 A 취소의 재고 이벤트·취소 이벤트이며, B/C 보상 이벤트는 없다. B/C의 DB 상태는 RESERVED인 채 Redis 상태는 각각 RESTOCKED/RELEASED다. same_event_retry_1, same_event_retry_2, expiration_after_rollback, user_cancel_after_rollback은 모두 Inventory release failed / TERMINAL_CONFLICT로 끝났다. 취소·보상 의도가 DB 트랜잭션과 함께 롤백되므로 기존 사가 대사도 이 복구 의도를 보존하지 않는다. 기존 통합 테스트는 성공한 보상과 확정 payload 실패 후 즉시 재확정만 확인하며 보상 자체의 롤백 후 복구는 다루지 않는다.
- 권장 수정: 주문 행 잠금 아래에서 롤백 후 남을 수 있는 실제 Redis 예약 상태와 취소·보상 의도를 함께 고려해, 이미 복원한 예약을 재확정하거나 부적절한 release로 다시 실패시키지 않도록 복구 경로를 마련한다. 이미 복원한 재고는 중복 증가시키지 않으면서 DB 취소·환불용 Outbox·eventId marker가 일관되게 완료돼야 한다. 이를 검증하는 실제 PostgreSQL·Redis 회귀 테스트에서 보상 기록 실패 후 동일/다른 eventId 재시도·만료·사용자 취소가 수렴하는지 확인한다. 정상 확정 이벤트 기록 실패의 DB 롤백 보장은 유지한다.

## 요구사항 대비 결론과 다음 역할

완료 조건 1~3의 주요 상태 전이와 완료 조건 5의 기존 개발 검증은 코드·보존 결과와 일치한다. 완료 조건 4 중 보상 롤백 후 복구에서 REV-001을 확인했으므로 완료 조건 6은 아직 충족하지 않았다. 다른 추가 finding은 없다.

제품·테스트는 수정하지 않았다. S3 TASK는 active·Independent Review: required로 유지하며 다음 별도 workflow-apply-review 세션에서 REV-001을 재검증·판정·반영하고 필요한 최종 검증을 수행한다. 그 전에는 S3를 completed로 바꾸거나 4단계를 시작하지 않는다.
