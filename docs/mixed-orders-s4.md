# 혼합 주문·부분 성공·고객 확인 — S4

Task ID: EC-INVENTORY-EXPANSION-20261020-S4

이 문서는 S4 완료 당시 계약입니다. S5에서 변경한 확인 후 결제·고정 주문 행·품목 취소/환불은 [S5 결제 계약](mixed-payments-s5.md)을 따릅니다.
갱신일: 2026-10-27 (Asia/Seoul)

S4는 입점 Redis와 실제 재고 서비스 예약 API를 조율한다. 혼합 주문의 구성 확인까지 제공하며 결제·할당·품목 환불은 S5, 출고·배송과 전체 구매 시나리오는 S6다. 혼합 주문은 고객 확인 뒤에도 `paymentReady=false`이며 `ORDER_RESERVED` 결제 이벤트를 발행하지 않는다. 기존 입점 단독 주문은 기존 예약·자동 결제 이벤트·멱등 응답을 유지한다.

## 요청과 조회

Gateway 외부 경로는 `/api/orders`, 주문 서비스 경로는 `/orders`다. 기존 access JWT 인증과 주문 소유권 검사를 사용한다. 직매입이 요청에 포함되면 아래 계약을 사용한다.

| API | 입력·응답 |
|---|---|
| POST /api/orders | 기존 `Idempotency-Key`, `{items:[{productId,quantity}]}`. 1~20개 서로 다른 상품과 양의 정수 수량. |
| GET /api/orders/{id} | 소유자의 최신 구성·예약 결과 조회와 제한된 복구 시도. |
| POST /api/orders/{id}/confirmation | 소유자, `{compositionVersion,totalPaymentAmount}`. 현재 구성·금액·유효시간과 같아야 한다. |
| POST /api/orders/{id}/cancel | 전체 시도를 먼저 종결한 뒤 예약을 해제한다. 미확인 해제가 남으면 202/PENDING, 모두 종결하면 200/CANCELED. |

가격·판매 유형·SKU를 클라이언트 입력으로 받지 않는다. 주문/품목/확인 요청의 알 수 없는 필드도 400으로 거부한다. 판매자 단위/품목 단위 혼합 주문 취소는 S5 전까지 409이며 전체 취소를 사용한다. 주문 확인의 버전·금액 불일치, 미확인·전부 실패·만료는 409다. 타인의 상세·확인·취소는 403이다.

`orderId`는 원격 호출 전에 커밋한 영속 주문 시도 ID다. 혼합 주문은 전부 실패해도 이 ID를 유지해 조회·재요청·복구한다. `sellerOrders`는 예약 성공 품목, `failedItems`는 확정된 업무 실패, `pendingItems`는 결과 미확인 또는 해제 대기다. 수량이 일부만 있으면 품목 전량 실패이며 요청 수량을 줄이지 않는다. `availableQuantity=0`은 가용량 추정치를 제공하지 않는다는 기존 필드의 보수적 값으로, 기술 오류를 품절로 확정하는 근거로 쓰지 않는다.

주요 응답은 기존 금액·품목과 `compositionVersion`, `confirmationRequired`, `confirmedVersion`, `pendingItems`, `paymentReady`다. 직매입 성공 품목에는 `warehouseId`가 있다. 미확인 품목이 하나라도 있으면 ORDER_PROCESSING이며 최종 확인·결제를 허용하지 않는다. 모두 확정되고 성공 품목이 있으면 ORDER_AWAITING_CONFIRMATION, 확인 뒤 ORDER_AWAITING_PAYMENT다. 전부 실패는 ORDER_FAILED다. 취소·만료는 종결 상태이며 늦은 응답이 되살리지 않는다. 구성 변화는 버전을 올리고 확인 버전을 지운다.

## 금액과 보존 형태

상품 읽기 모델의 판매자·유형·상품명·단가와 요청 수량을 최초 시도에 고정한다. 같은 키 재요청은 같은 시도·품목·예약 ID의 최신 진행 상태를 반환한다. 상품 가격이 바뀌어도 기존 원본은 바꾸지 않는다. 다른 요청 본문에 같은 키를 쓰면 409이며 키 보장 기간은 기존 24시간 설정을 따른다. 입점 단독의 저장 응답 재생 정책은 그대로다.

직매입 상품액에 서버의 배송비/무료 기준을 적용하고 고객 주문당 한 번만 계산한다. 입점 상품액은 직매입 무료 기준에 합산하지 않는다. 직매입이 전부 실패하면 배송비는 0이다. 배송비 전체를 성공 직매입 품목 중 안정적인 orderItemId 순서의 첫 품목에 배분한다. 직매입에는 판매자 수수료·자기 지급 정산을 만들지 않는다. 정수 금액의 곱·합은 오버플로를 거부한다.

S4의 `orders`는 시도의 소유권·총액·진행 상태를 보존하고, `mixed_order_attempt.items_json`은 원본 품목/판매자 그룹 ID·예약 ID·유형·단가·수량·현재 예약 결과·창고·입점 재고 사실 발행 여부를 보존한다. 성공 품목의 SellerOrderResponse/OrderItemResponse는 이 원본에서 구성한다. 기존 결제/배송용 `seller_orders`/`order_items` 행과 사가는 S4 혼합 시도에 생성하지 않는다. S5는 이 고정 ID와 snapshot을 사용해 결제용 주문으로 연결해야 한다. 따라서 혼합 시도는 S4의 판매자 주문 목록에 노출되지 않는다. 기존 입점 주문의 행·목록·취소·만료 경로는 그대로다.

## 트랜잭션·장애·복구

멱등 키 선점·주문 의도·각 품목 원본과 ID·조회 가능한 최초 응답을 하나의 로컬 DB 트랜잭션으로 커밋한 뒤 Redis/HTTP를 호출한다. 상태 반영과 입점 재고 Outbox는 다시 짧은 로컬 트랜잭션이다. 모든 상태 변경은 주문 행 잠금 아래 직렬화하고 HTTP 호출은 트랜잭션 밖에서만 허용한다.

직매입은 같은 ID로 GET 후 필요하면 PUT, 취소는 원본을 포함한 release다. 연결 제한 1초·본문 수신 완료까지 포함한 전체 HTTP 제한 2초(초과·중단 시 진행 중인 요청 취소), 한 작업 즉시 호출은 최초 포함 최대 3회, 간격은 250/500ms다. 실패·인증 미구성·응답 원본 불일치는 미확인으로 보존한다. 한 복구 패스는 30초를 기준으로 다음 품목 착수를 중단하므로 마지막 작업의 제한 시간만큼 더 걸릴 수 있다. 중단된 품목은 계속 영속 대기한다. 각 품목의 원격 작업 전에 다음 품목 위치(recovery_cursor)를 짧은 주문 잠금 트랜잭션으로 저장한다. 다음 회차와 새 애플리케이션 컨텍스트는 이 위치에서 원형 순회를 이어가므로 앞쪽 품목의 반복 지연이 뒤쪽 품목의 착수를 계속 막지 않는다. 품목 원본/응답 순서와 예약 ID·구성 버전은 그대로 유지한다.

입점도 같은 예약 ID와 고정 기한을 재사용한다. 혼합 주문의 품절 및 생성 전 해제를 Redis 종결 기록으로 남겨 늦은 요청을 차단한다. 기존 입점 단독 스크립트 호출 의미는 바꾸지 않는다. 입점 예약/해제 사실은 같은 로컬 상태 트랜잭션의 INVENTORY_STOCK_ADJUSTED Outbox로 한 번씩 발행하며 직매입은 이 경로로 보내지 않는다.

전용 단일 스레드 mixedOrderTaskScheduler의 복구 스케줄러는 패스 종료 후 5초 간격으로 DB의 nextRecoveryAt 순서로 최대 20개를 조회한다. 기존 Outbox·입점 만료 등은 별도의 기본 taskScheduler를 사용하므로 재고 HTTP 대기가 이 정기 작업의 실행 스레드를 점유하지 않는다. 처리 완료 후 다음 복구 시각을 갱신하며 단순 목록 조회는 복구 기한을 미루지 않는다. 취소·만료는 해제 의도를 먼저 커밋하고 결과가 확인될 때까지 재시도한다. 새 애플리케이션 컨텍스트도 같은 DB/예약 원본에서 재개한다. 기존 고아 예약 정리는 새 혼합 상태를 기존 입점 주문 만료로 처리하지 않으며 S4 복구가 고정 TTL을 담당한다.

## 로컬 실행과 스키마

| 설정 | 의미·기본값 |
|---|---|
| INVENTORY_SERVICE_URL | 기본 http://localhost:8088, Compose 내부 http://inventory-service:8088 |
| INVENTORY_SERVICE_TOKEN | 두 서비스의 동일한 전용 Bearer 값. 기본 비어 있음. 미구성은 직매입 미확인·결제 차단. |
| DIRECT_PURCHASE_DELIVERY_FEE | 기본 0. 운영 요율을 정하지 않은 개발 기본값. |
| DIRECT_PURCHASE_FREE_DELIVERY_THRESHOLD | 기본 Long.MAX_VALUE. 직매입 상품액 기준이며 테스트의 3,000원/100,000원은 fixture. |

실제 자격 증명은 만들거나 파일에 저장하지 않는다. 토큰을 로그/응답/검증 자료에 남기지 않으며 외부 네트워크에서는 TLS를 사용한다. Gateway internal 경로와 재고 포트를 공개하지 않는다. Compose order-service는 현재 소스의 로컬 빌드로 연결했으며 실제 이미지 빌드/전체 Compose 기동은 이번 검증과 구분한다.

V7__mixed_order_attempt.sql은 orders FK·금액/구성 버전·확인 버전·JSON 배열 제약과 복구 인덱스를 추가한다. 기존 주문 서비스의 dev/docker Hibernate update 및 prod 마이그레이션 정책을 유지한다. 실제 서비스/공유 DB에 SQL을 적용하지 않는다. prod는 기존 전체 기준선을 준비한 승인 절차가 필요하며 빈 DB에 주문 V7만 실행해 시작할 수 없다.

필수 로컬 검증은 `./gradlew :order-service:test :order-service:build --offline --no-daemon`이다. 실행 로그·XML·설정 검사·소스 기준선은 .workflow/results/EC-INVENTORY-EXPANSION-20261020-S4/에 보존한다. 실제 Kafka 전달·전체 Gateway HTTP 체인·강제 프로세스 종료·S5/S6 전체 구매 성공을 주장하지 않는다.
