# 배송 상태에 따른 주문 취소 — 4단계

Task ID: EC-CORRECTNESS-RESTART-20261007-S4
Status: active
Independent Review: required

## 목표와 범위

세션별 계획의 4단계만 구현한다. 대상은 /home/jybeomss/sideProject이며 D-001~D-004를 적용한다. S1~S3 변경·리뷰·근거를 보존한다. 5~6단계, 반품 수령·검수, ARL, 기존 데이터 이전, 배포는 제외한다.

## 설계

예약 주문은 기존 즉시 취소를 유지한다. 확정 주문은 판매자 주문별 고정 requestId와 PENDING 상태·사유·재요청 시각을 DB에 저장하고 Outbox로 배송 취소를 요청한다. 배송은 준비 중/아직 생성 전이면 CANCELED로 고정하고 승인하며, 이미 출고된 배송은 거절한다. 생성 전 취소도 seller_order_id UNIQUE 행을 만들어 늦은 확정을 막는다. 생성·취소의 동시 insert와 출고·취소를 동일 배송 행으로 직렬화한다. 승인 응답 이후에만 주문 취소·환불 Outbox·재고 복원을 실행한다. 중복 요청/응답은 고정 requestId로 수렴한다. DB/Outbox와 scheduler로 재기동 후 대기 요청과 준비 배송을 복구한다.

## 완료 조건

1. 준비 중 취소는 배송을 차단하고 승인 전에는 환불·재고 복원이 없다. 예약 취소와 확정 전 재고 실패 보상은 기존 안전성을 유지한다.
2. 출고와 취소 경합은 하나의 결정만 남기며 STARTED/COMPLETED/출고 후 FAILED는 취소 거절, 환불·재고 복원 없음으로 끝난다.
3. 취소→늦은 확정, 중복·재발행·동시 생성/취소, 응답 중복과 실패 롤백에서 배송 재생성·중복 환불·재고 증가가 없다.
4. 재기동을 가정한 새 트랜잭션/재요청으로 PENDING 복구가 가능하며 API는 202 대기·409 거절과 판매자별 상태를 제공한다.
5. 실제 PostgreSQL·Redis·Spring 프록시와 실제 배송/주문 소비 흐름으로 정합성·경합·롤백을 검증하고 직접 영향받는 테스트/빌드를 통과한다.
6. 별도 세션 독립 리뷰 및 필요한 반영 전에는 TASK를 completed로 바꾸지 않는다.

## 진행 상태

2026-10-19 workflow-develop에서 구현·개발 검증을 마쳤다. 확정 주문은 판매자별 requestId·PENDING을 기록하고 배송 승인 응답 이후에만 주문 취소·재고 복원·ORDER_CANCELED를 기록한다. 배송 준비 중/생성 전 취소는 CANCELED 행을 남겨 늦은 확정을 막고, 출고/완료/출고 후 실패는 거절한다. 출고 전 실패 보상도 승인 후에만 환불 사가를 진행한다.

배송 생성·취소는 seller_order_id UNIQUE와 insert-on-conflict 후 행 잠금, 출고·취소·완료·실패는 배송 행 잠금으로 보호한다. 승인 결정은 requestId와 함께 영속 보존하며 재발행에도 뒤집지 않는다. 주문/배송의 DB 상태·Outbox·marker는 같은 로컬 트랜잭션이다. 기존 Redis 멱등 복원은 승인 후 DB 기록 실패 재시도에서도 중복 증가와 재고 이벤트 유실을 막는다. 미발행 확정 전 재고 실패 보상은 명시적 별도 경로를 사용한다.

API는 PENDING=202, REJECTED=409, 잠금 불가=503, 취소 완료/기취소=200을 구분한다. 판매자별 cancellationStatus·requestId는 주문 조회에 포함한다. 여러 판매자 전체 취소는 판매자별로 승인/거절하며 남은 대기가 있으면 PENDING, 대기 해소 후 거절 판매자가 있으면 REJECTED를 반환한다. 준비 배송은 기본 30초 후 출고 scheduler가 처리하며 대기 취소는 60초 경과 후 같은 requestId로 재요청한다.

최종 `./gradlew :order-service:build :shipping-service:build --offline --no-daemon --console=plain --max-workers=2`가 BUILD SUCCESSFUL in 41s로 끝났다. 주문 108개·배송 4개를 실제 실행해 합계 112개, 실패·오류·생략 모두 0개다. 실제 PostgreSQL·Redis 주문 통합 56개(기존 34개를 새 계약에 맞춰 유지 + 신규 22개), API 상태 검증 5개를 포함한다. 양방향 실제 PostgreSQL Lock 대기, 출고/취소 및 생성/취소 경합 각각 3회, 중복·역전·응답 유실·기록 롤백·SQL CHECK/UNIQUE·판매자별 부분 거절·출고 전/후 배송 실패를 검증했다. 기존 예약 만료·결제 보상·Redis 복구 검증도 통과했다. git diff --check 통과, 테스트 컨테이너 종료 확인.

근거는 `.workflow/results/EC-CORRECTNESS-RESTART-20261007-S4/`의 build.log, test-results/, validation.json, source-manifest.json, baseline.json에 있다. S3 직전 TASK/HANDOFF/DECISIONS/계획은 before-*.md에 보존했다. S1~S3 자료와 기존 미커밋 변경을 보존했으며 payment·정산 소스는 기준선 hash와 동일하다.

개발 완료 조건 1~5를 검증했으며 별도 독립 리뷰는 남았다. TASK는 active·Independent Review: required, HANDOFF는 같은 ID의 active로 유지한다. 다음 별도 세션은 workflow-independent-review로 이 단계만 검토한다. 리뷰 finding이 있으면 이후 workflow-apply-review로 반영한다. 5~6단계는 미착수다. 커밋·배포·기존 DB 초기화·ARL 복원은 수행하지 않았다.

검증 한계: 실제 Kafka 전달·DLT·외부 PG는 실행하지 않았다. 프로세스를 실제 재기동하지는 않았고 신규 scheduler 인스턴스와 별도 커밋 트랜잭션에서 보류 요청/준비 배송 복구를 검증했다. 완료 worker는 mock이며 실제 완료/실패 서비스의 Spring 프록시와 행 잠금은 실행했다. 반품 수령·검수·출고 후 재판매 재고 복원은 이번 범위 밖이다.
