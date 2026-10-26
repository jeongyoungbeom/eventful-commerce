# 결함 보완 재시작의 설계 결정

## D-001 — 이전 완료 기록을 무효화하고 단계별 TASK로 재시작

Status: active
Scope: global

이전 보완 구현이 삭제됐으므로 2026-10-07 재시작 시점에서 1~6단계는 모두 미착수다. 이후 상태는 계획표와 현재 TASK·HANDOFF를 따른다. 롤백된 1단계 완료·리뷰 대기는 새 코드의 검증 근거로 사용하지 않는다. 계획 ID는 `EC-CORRECTNESS-RESTART-20261007`, 단계 N의 Task ID는 `EC-CORRECTNESS-RESTART-20261007-SN`이다. 재시작 시 실제 Task ID는 `EC-CORRECTNESS-RESTART-20261007-S1`이다.

각 TASK는 해당 단계의 완료 조건만 다룬다. 독립 리뷰와 필요한 반영을 마쳐야 단계 완료로 기록한다. 이 구조는 1단계 리뷰 완료를 전체 1~6단계 완료로 오인하지 않도록 한다.

## D-002 — ARL과 실험 코드 복원 제외

Status: active
Scope: global

사용자는 ARL 테스트에 연결할 하네스와 실험 코드를 제거하고 사용하지 않기로 했다. 이후 보완에서 관련 모듈·실패 주입·전용 문서·테스트 연결을 복원하지 않는다. 제품 correctness를 검증하는 정상 회귀·통합 테스트는 변경에 필요한 범위에서 작성한다.

## D-003 — 기존 DB 데이터 이전과 적용 이력 보존 제외

Status: active
Scope: global

사용자는 쌓인 데이터가 없으며 로컬 DB를 새로 초기화할 수 있다고 밝혔다. 그 로컬 개발 전제에서 과거 데이터 backfill, 기존 receipt 이전, legacy 호환 분기, 적용 이력 보존은 보완 완료 조건이 아니다. 새 스키마 정의와 UNIQUE·금액 정합성·트랜잭션은 유지하고 검증한다.

이 결정은 현재 로컬 보완에 한정한다. `docs/schema-migration-policy.md`의 prod 정책을 변경하지 않으며 운영·공유 DB 초기화 권한으로 해석하지 않는다. 이번 진행 문서 갱신에서는 DB를 초기화하지 않았다.

## D-004 — 완료된 정리와 빌드 기준선 유지

Status: active
Scope: global

기준 커밋은 `96c0e240420ef003e363d7078410dc3a34abc741`이다. 코드 정리 커밋 `f0f7ad9`와 빌드 환경·LF 수정 커밋 `96c0e24`는 유지한다. Gradle 8.14.5, Kotlin 플러그인·런타임 2.2.21, JDK 21을 기준으로 한다. 이 기준선의 기존 테스트 110개 통과는 보완 1~6단계 완료를 뜻하지 않는다.

## D-005 — 환불 ID 기록·정산 잠금과 원본 내용 비교

Status: active
Scope: task:EC-CORRECTNESS-RESTART-20261007-S1

환불 payload 전체를 받아 대상 결제·주문·판매자를 검증한다. refundId를 `settlement_refund`의 PK로 삼고 정산 FK·금액·사유·원본 환불 시각을 보존한다. 같은 ID의 동일 내용만 정상 중복으로 처리한다. payment는 환불 생성 시 기본 현재 시각과 명시적 입력을 모두 마이크로초로 절삭한다. 최초 저장·최초 Outbox·DB 재조회·대사 재발행이 같은 시각을 사용해야 한다. 정산은 발행자가 확정한 시각을 ISO 문자열로 보존하고 완전 비교하여 실제 다른 시각을 정상 중복으로 허용하지 않는다. 발행자 timestamp 정밀도 손실은 문자열 저장만으로 해결되지 않으므로 생성 시 정규화한다. 이 계약은 REV-001 반영에서 결정적 나노초·초 경계 입력과 실제 PostgreSQL/Spring 저장·별도 조회·재발행으로 검증했다.

환불 처리 기록과 정산 갱신은 eventId marker와 같은 로컬 DB 트랜잭션이다. 같은 정산의 환불은 비관적 행 잠금으로 직렬화하고 `@Version`은 기존 다른 writer의 오래된 저장을 거부한다. 새로운 assigned ID 기록은 insert로 처리해 교차 정산 경합이 기존 기록을 merge하지 못하게 한다. 실제 PostgreSQL 경합·제약·롤백 검증이 통과했다.

환불 수수료는 정산의 실제 잔여 수수료와 총액 비율로 배분한다. 정수 나눗셈의 반올림 잔액은 최종 환불에서 소진한다. BigInteger 중간 곱셈으로 Long 오버플로를 피하며, 총액·수수료·판매자 잔액은 음수가 되지 않아야 한다. 과거 receipt backfill·데이터 이전·ARL과 5단계의 배치 페이징 변경은 추가하지 않는다.

## D-006 — Payment 행 직렬화와 판매자 주문별 대기 집합

Status: active
Scope: task:EC-CORRECTNESS-RESTART-20261007-S2

모든 기존 Payment 갱신 진입점은 orderId의 PESSIMISTIC_WRITE 행 잠금을 사용하고, @Version은 오래된 detached writer의 갱신을 거부한다. 공개 대기 환불 API도 orderId를 받아 자체 잠금·트랜잭션을 얻는다. 결제 생성은 기존 orderId UNIQUE를 유지하며 기존 결제 상태를 수정하지 않는다.

기존 pendingCancellationPayload TEXT에 판매자 주문별 단일 OrderCanceledPayload 배열을 저장한다. 같은 sellerOrderId의 같은 판매자·금액은 최초 사유·내용을 유지하고 서로 다른 판매자는 누적한다. 초기 자료가 없는 로컬 전제에 따라 단일 객체 legacy JSON과 OID 자료 변환은 추가하지 않는다. 별도 저장소·테이블은 만들지 않는다. 성공 결제는 각 대기를 한 번 환불하고 집합·flag를 비우며 실패 결제도 종료된 대기를 비운다.

환불은 결제/사용자 식별자·양수·잔여 결제 한도를 검사한다. 합계 대신 남은 금액에서 차례로 차감해 Long 오버플로를 방지한다. 같은 판매자 주문의 실제 환불은 판매자·금액이 같아야 한다. 대사 reason이 다른 것은 최초 환불 사실을 덮어쓰지 않으며 기존 refundId·사유·시각을 재발행한다. 신규 환불이 없는 일반 중복 취소는 상태·금액을 바꾸지 않는다.

결제·대기·환불·Outbox·멱등 marker는 같은 로컬 트랜잭션이다. Payment 서비스 진입점에 rollbackFor=Exception을 적용해 JSON checked 예외도 롤백한다. V3에 version과 결제 금액 범위·환불 양수 CHECK를 정의하고 기존 환불 UNIQUE를 유지한다. 실제 PostgreSQL/Spring 프록시에서 경합·Lock 대기·제약·롤백·재시도를 검증했다. 최종 72개 통과는 개발 검증 근거이며 독립 리뷰 완료를 뜻하지 않는다.

## D-007 — 주문 행 직렬화와 예약 판매자 단위 확정·만료

Status: active
Scope: task:EC-CORRECTNESS-RESTART-20261007-S3

확정·전체/판매자 취소·만료는 같은 Orders 행의 PESSIMISTIC_WRITE 잠금을 얻고 이후 판매자/품목을 읽는다. @Version은 유지한다. 만료는 일반 사용자 취소와 분리된 트랜잭션에서 잠금 이후 상태·expiresAt·RESERVED 대상을 검사한다. 남은 예약이 없으면 expiresAt을 비운다. 만료 실행은 Redis 취소 잠금을 필요로 하지 않으며 일반 취소는 기존 Redis→DB 순서를 유지한다. 이미 DB 잠금을 가진 재고 확정 실패 보상은 executor를 직접 호출해 DB→Redis 잠금 역전을 피한다.

ORDER_PARTIALLY_CANCELED도 남은 RESERVED 판매자가 있으면 결제를 확정한다. 취소된 판매자·품목을 재확정하지 않으며 새 ORDER_CONFIRMED의 sellerOrders와 totalAmount에는 이번 확정 대상만 담는다. Orders 원래 결제 총액과 취소/환불 payload는 유지한다. 확정 이벤트 기록 실패는 재고 실패 보상으로 삼지 않고 로컬 DB·marker를 롤백한다. Redis commit은 멱등 재시도한다.

새 통합 검증은 격리 실제 PostgreSQL·Redis와 실제 ShippingService·저장소를 사용한다. order-service의 shipping-service 의존성은 테스트 전용이다. 스키마·공통 이벤트 타입·Redis Lua·배송 제품 코드는 변경하지 않았다. 개발 검증 통과는 독립 리뷰 완료를 뜻하지 않는다.

2026-10-19 별도 workflow-apply-review에서 S3 REV-001을 반영했다. DB RESERVED 취소의 release가 TERMINAL_CONFLICT이면 기존 restock Lua로 COMMITTED/RESTOCKED를 검증·멱등 복원한다. NOT_FOUND/CORRUPTED와 복원 실패는 계속 롤백한다. DB가 아직 미취소인 대상은 APPLIED/ALREADY_APPLIED 모두 재고 Outbox를 취소와 같은 트랜잭션에 기록한다. 이미 DB CANCELED인 대상은 행 잠금 아래 제외하므로 커밋된 이벤트를 중복 생성하지 않는다. 실제 PostgreSQL·Redis 복구 12개와 실패 차단 단위 검증을 추가했고 최종 주문 81개·주문/배송 빌드가 통과했다. 별도 독립 리뷰 및 필요한 반영을 마쳐 S3는 completed다.

## D-008 — 배송의 영속 취소 결정 이후에만 확정 주문을 취소

Status: active
Scope: task:EC-CORRECTNESS-RESTART-20261007-S4

예약 취소는 즉시 처리하고 확정 주문은 판매자별 고정 requestId·PENDING·사유·재요청 시각을 저장해 SHIPPING_CANCELLATION_REQUESTED를 보낸다. 승인 응답의 주문·구매자·판매자·requestId를 대조한 뒤에만 기존 취소/재고/환불 Outbox를 실행한다. 최초 사유와 요청 ID를 유지하고 중복 응답은 추가 효과 없이 처리하며 상반된 결정은 거부한다. 기본 API 의미는 202 대기·409 거절·503 잠금 재시도·200 취소 완료/기취소다. 전체 취소는 판매자별로 결정하며 대기 우선, 대기가 없으면 거절 우선으로 요약하고 조회에서 각 판매자의 결과를 제공한다.

배송은 PREPARING 또는 출고 전 FAILED만 취소 승인한다. 생성 전 요청도 seller_order_id UNIQUE의 CANCELED 행을 만들어 늦은 ORDER_CONFIRMED를 막는다. ON CONFLICT DO NOTHING 후 같은 행을 잠가 생성/취소의 부재 경합을 보호하고 다판매자 생성은 ID 순으로 잠근다. 출고는 PREPARING에만 허용하며 취소/출고/완료/실패는 같은 행 잠금을 사용한다. STARTED·COMPLETED·shippedAt이 있는 FAILED는 거절하고 출고 후 오류를 환불/재판매 재고 복원 근거로 삼지 않는다. 반품 수령·검수는 추가하지 않는다.

주문 확정 직후 즉시 출고하던 동작 대신 기본 30초 준비 후 출고 scheduler를 사용하며 DB의 준비 배송을 재기동 후 처리한다. 보류 취소는 60초가 지나면 같은 requestId로 새 Outbox를 만들고 배송은 최초 결정을 재응답한다. 요청·결정·marker·Outbox는 각 로컬 DB 트랜잭션이며 직렬화 checked 예외도 롤백한다. 미발행 ORDER_CONFIRMED 이전 재고 실패 보상만 expiresAt/주문 상태를 검사하는 전용 메서드로 직접 복구한다. Redis의 멱등 복원·재고 Outbox 복구를 유지한다.

2026-10-19 개발 검증은 주문 108개·배송 4개와 두 서비스 build가 통과했다. 실제 PostgreSQL·Redis 주문 통합 56개, 새 API 검증 5개를 포함한다. 독립 리뷰는 다음 별도 세션에 남아 있다. 과거 자료 이전·실제 외부 PG·실제 Kafka 브로커/프로세스 재기동 검증은 추가하지 않았다.
