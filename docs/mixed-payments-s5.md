# 혼합 주문 결제·품목 취소·환불 — S5

Task ID: EC-INVENTORY-EXPANSION-20261020-S5
상태: 독립 리뷰 Major 1건 반영·필수 회귀 검증 완료 (S5 completed)

## 결제 원본과 상태

S4의 예약·부분 성공·고객 확인을 이어받는다. `POST /orders/{orderId}/confirmation`은 서버 구성 버전·금액·기한·예약 성공을 검증하고 최초 한 번만 기존 SellerOrder/OrderItem 행과 ORDER_RESERVED를 같은 주문 DB 트랜잭션에 생성한다. S4가 미리 발급한 판매자 주문·품목·예약 ID를 그대로 사용한다. 확인 전에는 paymentReady=false이며 확인 후 결제 대기 중에만 true다. 전부 실패·미확인·버전/금액 불일치·만료는 결제를 생성하지 않는다.

한 ORDER_RESERVED에 입점·직매입 금액을 모두 담아 Payment 하나를 생성한다. compositionVersion=confirmedVersion이 없는 직매입 이벤트는 거부한다. 결제 snapshot에는 판매 주체·방식·품목 원본·단가/수량·금액·최초 배송비 배분을 보존한다. 이후 취소·상품 정보 변경으로 이 금액을 다시 계산하지 않는다. 기존 입점 단독 주문은 자동 예약/결제·저장 응답 재생을 유지한다.

혼합 시도의 paymentState는 NONE → READY → COMPLETED 또는 FAILED다. paymentResultJson은 최초 완료 사실을 보존하며 재전달의 다른 원본을 거부한다. mixedOrder 표식으로 기존 주문 만료·고아 Redis 정리를 우회하고, 기존 혼합 복구 스케줄러가 품목별 상태를 관리한다. 기존 입점 사가의 판매자별 환불 개수를 직매입 품목 개수로 재해석하지 않는다.

## 결제 후 할당과 보상

결제 완료 구성·구매자·금액을 주문 원본과 비교한 뒤 주문 행 잠금 안에서 READY의 고정 기한을 먼저 검사한다. 기한과 같거나 지난 늦은 성공은 완료 원본을 보존하되 예약 품목을 취소·원본 금액 환불로 연결한다. 조회나 만료 스케줄러가 먼저 실행될 필요는 없다. 유효한 RESERVED 품목만 ALLOCATE_PENDING으로 먼저 커밋한다. DB 트랜잭션 밖에서 입점은 실제 Redis Lua 확정 시점의 서버 시각으로 만료를 원자적으로 재검사하고, 직매입은 POST /internal/reservations/{reservationId}/allocate를 호출한다. 이미 COMMITTED인 원본의 재시도는 기한 이후에도 멱등 성공이며 기존 입점 단독 확정·복구 계약은 유지한다. 성공 품목은 ALLOCATED/CONFIRMED다. 직매입의 onHand는 그대로이며 reserved만 allocated로 이동한다.

일시 오류·응답 유실은 할당 미확인으로 보존하고 동일 ID로 재시도한다. 만료·해제·품질/창고 부적합 같은 종결 실패는 해당 직매입 품목만 CANCEL_PENDING → CANCELED로 보상한다. 입점은 기존 판매자 주문 취소 단위를 유지한다. 다른 성공 품목은 보존한다. 보상 중 품목은 CANCELLATION_PENDING이며 직매입 자동 ORDER_CONFIRMED/배송 이벤트를 발행하지 않는다. 입점 확정 이벤트만 기존 배송 경로로 보낸다.

결제 전 취소/만료 이후의 늦은 성공은 이미 취소한 원본 금액을 환불하며 품목을 다시 확정하지 않는다. 결제 실패도 예약을 해제하며 실제 환불을 만들지 않는다. 원격 성공 후 로컬 결과/Outbox가 롤백되어도 먼저 커밋한 의도와 같은 예약 ID에서 복구한다.

## 취소 API와 재고 종결

- POST /orders/{orderId}/items/{orderItemId}/cancel: 구매자 본인의 직매입 품목 전량 취소. 수량 일부 취소는 제공하지 않는다.
- POST /orders/{orderId}/seller-orders/{sellerOrderId}/cancel: 직매입 묶음의 각 품목 취소 또는 기존 입점 판매자 단위 취소.
- POST /orders/{orderId}/cancel: 전체 취소. 입점 확정 주문은 기존 배송 승인/거절을 기다린다.

200은 취소 완료/기취소, 202는 재고 또는 배송 확인 대기, 409는 배송 취소 거절이다. 상세 조회의 품목 상태와 pendingItems로 진행 상황을 확인한다. 타인 주문은 403이다. 결제 확인 전 부분 취소 대신 기존 전체 취소를 사용한다.

직매입의 전용 POST /internal/reservations/{id}/cancel은 기존 내부 서비스 토큰과 원본 request를 요구한다. 예약→SKU 잠금으로 allocate와 직렬화하고 RESERVED 또는 ALLOCATED를 CANCELED로 종결한다. onHand를 늘리지 않고 reserved/allocated·원장·Outbox를 한 재고 DB 트랜잭션에 변경한다. 생성 전 취소도 원본 tombstone을 남겨 늦은 reserve/allocate를 막는다. 기존 release의 ALLOCATED 거절은 유지한다.

S5에는 피킹·실제 출고가 없다. S6의 피킹/출고는 반드시 같은 예약 잠금과 상태 전이에 참여하고, 피킹 이후 창고 확인 및 출고 후 취소 거절을 구현해야 한다. 이 내부 cancel을 출고된 재고에 그대로 적용해서는 안 된다.

## 품목 환불과 정산

입점 환불 키는 sellerOrderId, 직매입 환불 키는 orderItemId다. payment_refund는 (payment_id, refund_key) UNIQUE와 salesType/orderItemId/refund_key의 CHECK를 사용한다. 하나의 플랫폼 SellerOrder에서 여러 품목을 각각 한 번 환불할 수 있다. direct 취소 payload의 orderItemId·상품·수량·상품액·판매 주체를 결제 snapshot과 비교하고 상품액+최초 deliveryFeeAllocation만 환불한다. 배송비 배분은 취소 뒤 다른 품목으로 옮기거나 추가 청구하지 않는다.

Payment 행 잠금·총환불 한도·양수·동일 원본 검증과 대기 취소 집합은 유지한다. 결제 전 취소도 품목 키로 누적하며 성공 결제 이후 각각 한 번 처리한다. 일반 중복은 새 환불을 만들지 않는다. 대사 요청은 같은 refundId·최초 사유·마이크로초 시각을 재발행한다.

혼합 복구는 환불 요청과 품목별 receipt를 보존한다. PAYMENT_REFUNDED의 결제·판매자·품목·금액과 최초 receipt 원본을 비교하고, 수신 전까지 같은 취소 대사 명령을 재발행한다. 완료된 receipt는 다시 집계하지 않는다. 입점 정산만 생성하며 DIRECT_PURCHASE 결제/환불은 기존 정산 조회·차감에서 제외한다. 입점의 settlement_refund receipt 및 수수료 계산은 유지한다.

## 스키마와 검증 범위

order V8은 mixedOrder 표식·혼합 결제 상태를, payment V5는 기존 판매자 주문 UNIQUE를 품목 환불 키로 교체하는 변경을, inventory V23은 CANCELED와 할당 취소 원장 제약을 추가한다. 기존 dev/docker의 Hibernate update와 prod의 승인된 baseline/Flyway 정책을 유지한다. **기존 개발 DB에는 payment V5의 UNIQUE 교체와 order V8이 필요하다. Hibernate update만으로 기존 UNIQUE를 제거할 수 없다.** 이 작업은 격리 테스트 DB에서만 SQL을 실행하고 실제 서비스/공유 DB에는 적용하지 않는다.

S5 테스트는 세 격리 PostgreSQL DB(주문·재고·결제/정산)·Redis·실제 재고 HTTP·Spring 결제/정산 프록시를 연결한다. 이벤트는 실제 서비스 소비자로 직접 전달하며 Kafka 브로커 전달을 검증한 결과는 아니다. 외부 PG 기본 503·보호된 로컬 시뮬레이션을 유지한다. S6의 창고별 출고·전체 Kafka 구매 시나리오와 운영 가능 여부는 별도 검증 대상이다.

검증 결과: 최종350개(공통14·주문159·결제84·재고40·배송5·정산48), 실패/오류/생략0개. 신규 S5 통합19개와 전체 모듈 build -x test·최종 order build가 성공했다. 이전 성공·후속 성공 집계와 제한은 S5 결과 폴더 DEVELOPMENT.md·validation.json을 따른다.

리뷰 반영 검증(2026-10-27): 만료 조회 전 결제 완료·기한 경계·실제 Redis 확정 지연을 보완했다. 관련 회귀 131개와 order-service build(1분51초)가 실패/오류/생략 없이 성공했다. 이전350개는 보존한 개발 근거이며 이번 결과와 단순 합산하지 않는다. 상세는 reviews/EC-INVENTORY-EXPANSION-20261020-S5/RESOLUTION.md와 S5 결과 폴더 apply-review/에 있다.
