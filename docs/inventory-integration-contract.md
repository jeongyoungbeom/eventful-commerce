# 판매 주체·재고 연동 계약 — S1

Plan ID: EC-INVENTORY-EXPANSION-20261020
Task ID: EC-INVENTORY-EXPANSION-20261020-S1
갱신일: 2026-10-27 (Asia/Seoul)

이 문서는 S1에서 구현한 계약과 S2~S6에서 구현할 API를 구분한다. S2에서 `inventory-service`의 창고·발주·입고·검수·원장과 관리자 JWT 검증을 구현했다. S5의 확인 후 혼합 결제·품목 취소/환불은 구현·필수 검증 완료 후 독립 리뷰 대기이며 창고 출고·전체 구매 플로우는 S6 범위다. 상세 API와 실행 경계는 [S2 재고 서비스 가이드](inventory-service-s2.md)를 따른다. 기존 보호된 결제 시뮬레이션과 실제 PG 미구성 503 정책은 유지한다.

## 1. 현재 판매 주체와 권한

입점의 `sellerId`는 기존 user-service의 SELLER 로그인 주체 UUID다. 공식 판매 주체는 로그인 계정과 분리한 예약 UUID `00000000-0000-0000-0000-000000000001`이며 product-service의 `official_seller` 단일 레코드로 발급·참조한다. 표시 이름이 같거나 바뀌어도 ID·판매 방식·소유권은 바뀌지 않는다. 공급사는 판매 주체가 아니다. 이 UUID를 로그인 계정으로 발급하지 않는다.

| 현재 외부 경로 | 권한과 입력 | 결과 |
|---|---|---|
| PUT /api/products/official/seller | ADMIN access JWT, `{ "displayName": "공식 판매자명" }` | 공식 ID를 최초 발급하거나 같은 ID의 이름만 갱신한다. 동시 요청은 PK upsert로 단일 주체를 유지한다. |
| GET /api/products/official/seller | ADMIN access JWT | 같은 공식 ID와 현재 표시 이름을 참조한다. 미발급이면 400이며 먼저 관리자가 발급한다. |
| POST /api/products/official | ADMIN access JWT, 기존 상품 multipart request/images | DIRECT_PURCHASE 상품을 생성한다. sellerId·salesType은 서버가 지정하고 초기 stock은 0만 허용한다. |
| PUT/DELETE /api/products/official/{productId} | ADMIN access JWT | 해당 공식 주체의 직매입 상품만 수정·비활성화한다. 타인의 입점 상품에는 이 경로를 사용할 수 없다. |
| 기존 POST/PUT/PATCH/DELETE /api/products... | 기존 SELLER 권한·소유권 | 입점 상품만 생성·수정한다. 공식 ID를 일반 판매자 경로에 전달하거나 공식 상품을 수정·수량 변경하면 거부한다. |

상품 서비스의 공식 관리 controller와 service 메서드 모두 ADMIN을 검사한다. 일반 판매자 경로는 역할 계층상 ADMIN에게도 허용되지만 타인의 상품 소유권을 우회하지 않는다. 공식 참조의 공개화는 하지 않는다. 고객 화면용 표시 정보 공개는 후속 요구사항으로 정한다.

기존 외부 인증 경계는 Nginx → Gateway(access JWT 서명·만료·유형·블랙리스트) → 비공개 상품 서비스다. 상품 서비스는 기존 GatewayAuthFilter가 설정한 역할·UUID로 권한을 검사한다. 헤더만으로 서비스에 직접 접근할 수 있는 공개 배포를 허용하지 않는다. 이번 변경은 기존 서비스 포트를 공개하거나 새 비밀을 만들지 않는다.

## 2. 상품·SKU와 불변 판매 방식

| 값 | 입점 | 직매입 |
|---|---|---|
| salesType | MARKETPLACE | DIRECT_PURCHASE |
| sellerId | 인증된 입점 판매자의 UUID | 공식 주체의 예약 UUID |
| skuId | productId와 같음 | productId와 같음 |
| inventorySource | SELLER_REDIS | INVENTORY_SERVICE |
| S1 판매 승인 | 기존 예약 경로 | DIRECT_PURCHASE_NOT_READY로 품목 제외 |
| S1 상품 stock | 기존 입점 수량 투영 | 0, 판매 승인 근거로 사용하지 않음 |

상품 하나가 SKU 하나다. 별도 SKU 번호·매핑 테이블을 만들지 않는다. 다른 용량·색상·다른 판매 방식은 새 상품 ID로 등록한다. 상품의 sellerId·salesType은 일반 수정 입력에 없고 JPA에서도 update 대상이 아니다. 기존 상품의 판매 방식 전환 API는 제공하지 않는다. 같은 물건을 양쪽에서 판매해도 서로 다른 상품/SKU·재고다.

DB CHECK는 공식 ID와 DIRECT_PURCHASE의 대응 및 S1 직매입 stock=0을 강제한다. 로컬 dev/docker의 Hibernate update는 CHECK 배포를 보증하지 않는다. 운영 스키마는 기존 schema-migration-policy.md에 따라 별도 승인된 절차로 적용한다. 이번 검증은 임시 PostgreSQL에 DDL을 실행하며 서비스 DB에 SQL을 적용하지 않았다.

## 3. 상품 이벤트 버전과 Redis 경계

PRODUCT_REGISTERED와 PRODUCT_UPDATED는 새 발행에서 `schemaVersion=2`, `salesType`, `skuId=productId`를 보낸다. 기존 필드만 있는 JSON은 v1·MARKETPLACE·skuId=productId로 읽는다. v1은 공식 ID나 직매입을 표현할 수 없으며 미래 버전, ID/유형 불일치와 SKU 불일치는 거부한다. PRODUCT_UPDATED의 informationVersion은 상품별 증가값이다. 행 잠금 아래 더 큰 버전의 정보만 반영하고 기존 상품의 주체·판매 방식을 바꾸지 않는다. PRODUCT_STOCK_UPDATED도 버전과 salesType을 포함하며 현재는 입점 전용이다.

직매입 등록은 주문 읽기 모델까지 전달하되 Redis를 초기화하지 않는다. 직매입 정보 변경은 읽기 모델의 이름·단가만 바꾼다. 기존 수량 이벤트로 직매입을 증감하려는 요청은 거부한다. 기존 Redis 대사 scheduler는 직매입을 조회·보정하지 않고 상품 서비스의 Order-side delta/snapshot 적용도 직매입을 거부한다. S2의 직매입 잔량 이벤트를 기존 PRODUCT_STOCK_UPDATED 경로에 연결하지 않는다.

PRODUCT_DEACTIVATED는 기존 productId를 조회해 상태만 내리며 판매 방식이나 재고를 바꾸지 않는다. 비활성화가 늦은 상품 정보 변경으로 되돌아오지 않는 기존 계약을 유지한다. Outbox·멱등 marker와 서비스 DB 트랜잭션 경계를 유지한다. Kafka 전달 자체·재균형은 이번 테스트 범위에 포함하지 않는다.

## 4. 주문 snapshot과 호환 계약

S1의 주문 판매 snapshot 계약은 v1이다. 판매자 주문의 sellerId·salesType, 그 유형에서 고정적으로 결정되는 inventorySource, 품목의 productId=skuId·productName·unitPrice·quantity·totalAmount·reservationId, 품목별 deliveryFeeAllocation과 판매자별 deliveryFee·수수료·정산액을 사용한다. 기존 입점 배송비와 새 품목 배분은 현재 0이다. 금액은 정수 원 단위 Long이다.

판매 주체·유형은 주문 시점에 SellerOrder에 복사하고 상품을 다시 조회해 취소·환불·정산의 유형을 결정하지 않는다. ProductReadModel이나 상품명·가격이 변경돼도 이미 저장한 주문의 단가·금액·유형은 유지된다. inventorySource는 v1의 salesType 매핑에서 도출하므로 별도의 변경 가능한 경로 설정을 두지 않는다. 판매 방식별 경로 의미가 달라지면 새 snapshot 버전·명시적 마이그레이션을 설계해야 한다.

OrderReservedSellerPayload, OrderConfirmedSellerPayload, OrderCanceledSellerPayload, PaymentCompletedSellerPayload, PaymentRefundedPayload에 불변 salesType을 전달한다. 과거 JSON의 필드 누락은 MARKETPLACE이며 공식 ID를 입점으로 해석하지 않도록 ID/유형 조합을 검증한다. 새 필드는 같은 저장소의 공통 계약을 배포하는 소비자와 함께 적용한다. 구버전 바이너리와의 독립 롤링 배포 호환성을 보증하지 않는다.

S1 시점에는 PaymentService가 직매입 이벤트를 거부했다. S5는 고객 확인 버전·금액 원본을 검증한 혼합 결제와 품목별 환불을 제공한다. ShippingService는 직매입 ORDER_CONFIRMED를 기존 단일 자동 배송으로 생성하지 않는다. 정산 소비자는 직매입 결제·환불을 입점 정산 생성·차감에 보내지 않는다. 알림은 이 계약을 읽을 수 있으며 재고 변경 권한을 갖지 않는다. 이 분기는 직매입 구매 기능의 완성을 뜻하지 않는다.

## 5. S2 관리자 인증 구현과 S3 내부 인증 계약

S2의 inventory-service는 Gateway의 X-User-Id/X-User-Role만 믿고 업무를 허용하지 않는다. 관리 API는 전달된 access JWT의 서명·만료·tokenType을 직접 검증하고 ADMIN만 허용한다. SELLER·USER·refresh token·헤더 위조는 거부한다. 초기에는 기존 ADMIN으로 작업하고 창고별 작업자 권한은 해당 역할·허용 창고 ID의 발급과 검증이 준비된 뒤에만 허용한다. 공급사는 권한 주체가 아니다.

S3는 [예약 업무 계약](inventory-reservations-s3.md)의 서버 간 `/internal/**` 예약 API는 order-service 전용 `INVENTORY_SERVICE_TOKEN`의 Bearer 인증을 독립 검증한다. 사용자 access JWT와 다른 자격 증명이며 관리자 JWT로 내부 예약 API에 접근할 수 없다. 설정이 없으면 internal API를 기본 차단하고 수신 값은 상수 시간 비교한다. 공개 Gateway 라우트에 internal 경로를 추가하지 않는다. 로컬은 격리 내부 네트워크, 외부 네트워크 사용 시 TLS를 요구한다. 실제 자격 증명은 소스·문서·로그·테스트 결과에 기록하지 않는다. 발급·주입·회전은 S2/S3의 실행 설정과 함께 구성한다.

S3에 구현한 계약은 `PUT /internal/reservations/{reservationId}`(생성), `GET`(결과 확인), `POST .../release`, `POST .../allocate`다. 주문 시도·orderItemId·skuId·quantity·만료 시각을 영속화한 뒤 같은 reservationId로 요청한다. 같은 ID·같은 입력은 같은 업무 결과, 다른 입력은 409다. release가 생성보다 먼저 오면 원 입력의 종결 기록을 남겨 늦은 생성이 되살리지 못하게 한다. 순서 역전 때도 해제 요청은 원 입력을 포함한다. 미확인 결과는 품절과 구분해 조회·재시도한다. 상태·경합·TTL·오류 HTTP 응답과 수량 로트 검증은 S3 예약 업무 계약을 따른다. order-service 호출 조율은 S4에 구현한다.

## 6. S4 최종 구성·확인 계약

혼합 주문은 성공/실패 품목과 서버 계산 최종 itemAmount·직매입 deliveryFee 1회·totalPaymentAmount·compositionVersion·expiresAt·confirmationRequired를 반환한다. 품절은 수량을 줄이지 않고 품목 전체를 제외하며 타임아웃은 결과 미확인으로 기록한다. 주문 시도와 품목 예약 ID를 먼저 저장하고 DB 행 잠금을 보유한 채 재고 API를 호출하지 않는다.

예정된 `POST /api/orders/{orderId}/confirmation`은 소유 USER의 access JWT와 `{ compositionVersion, totalPaymentAmount }`를 받고 서버가 저장한 구성·버전·금액·유효시간과 대조한다. 클라이언트의 가격·판매 방식·SKU 대체 입력을 받아들이지 않는다. 미확인 예약이 없고 성공 품목이 남아 있을 때만 확인 버전을 영속화한다. 구성이 바뀌거나 예약이 만료되면 버전을 올리고 기존 확인은 무효다. 확인 전에는 결제 가능 주문 이벤트를 발행하지 않는다. 전부 실패하면 결제를 생성하지 않는다.

S4의 실제 혼합 주문 API·영속 원본·부분 성공/미확인·복구·확인·배송비와 실행 설정은 [S4 혼합 주문 계약](mixed-orders-s4.md)을 따른다. 입점 단독은 기존 자동 예약/이벤트와 저장 응답 재생을 유지한다. 직매입이 포함된 시도는 별도 고객 확인을 요구하지만 S5 전까지 paymentReady=false와 결제 이벤트 미발행을 유지한다. 위 ORDER_RESERVED 발행 시점의 예정 계약은 S5의 결제용 snapshot 연결 이후에 적용한다. 혼합 시도의 고정 품목/판매자 ID는 S4 원본에 보존하며 S5가 기존 결제 테이블·사가로 연결한다.

## 7. S5 환불·정산과 S6 배송 계약

S5의 구현 계약은 [S5 결제·취소·환불](mixed-payments-s5.md)을 따릅니다. 아래 표는 S1 설계 시 예상 연결부이며 S5에서는 별도 refundScope 대신 salesType·orderItemId와 (payment_id, refund_key) UNIQUE로 같은 범위를 표현합니다. S6 배송/창고 기능은 미구현입니다.

| 후속 단계 | 필요한 식별·보존 | 변경해야 할 현재 caller·제약 |
|---|---|---|
| S5 품목 취소 | cancelRequestId·refundId·orderItemId·sellerOrderId·salesType, 품목 전량·원 비용 배분 | OrderCancelExecutor와 SagaReconciliationService의 판매자 취소, OrderSagaRefundReceipt의 sellerOrder 단위 receipt/예상 환불 수, PaymentService의 대기 취소 및 대사 |
| S5 환불 멱등 | refundScope=SELLER_ORDER/ORDER_ITEM 및 targetId; 같은 요청 원본 비교·총환불 한도 | PaymentRefund의 (payment_id, seller_order_id) UNIQUE를 (payment_id, refund_scope, target_id)로 변경한다. 입점은 판매자 주문, 직매입은 원 품목을 target으로 사용한다. refundId receipt와 원 사유·시각 재발행은 유지한다. |
| S5 금액 | 품목 상품액·배송비 원 배분, 확정한 최종 구성 버전 | PaymentCompleted·PaymentRefunded 발행자/소비자, Orders/SellerOrder/OrderItem snapshot 및 DTO. 배송비를 중복 환불하거나 취소 뒤 추가 청구하지 않는다. |
| S5 정산 | 입점 수수료·실제 남은 정산액과 refundId 원본 비교 | PaymentEventsConsumer·SettlementService·SettlementRefund. DIRECT_PURCHASE는 자기 지급·입점 정산 조회·차감 대상이 아니다. |
| S6 출고·배송 | outboundOrderId·warehouseId·orderItemId·allocation/reservationId·원 로트 | Shipping의 seller_order_id UNIQUE와 자동 출고 경로. 입점은 기존 판매자 단위, 직매입은 outboundOrderId 기준 여러 배송이며 실제 출고 전 자동 시작하지 않는다. |
| S6 취소·반품 | 품목 취소 요청·창고 승인 결정·실제 출고·반품 수령·검수 | 기존 ShippingCancellation payload는 입점 전용으로 유지하고 직매입 창고 취소 결정·반품 API를 추가한다. 환불 성공만으로 실물 재고를 복원하지 않는다. |

위 필드·제약·상태 전이·해당 단계의 이벤트 버전은 S5/S6 구현에서 함께 적용한다. 현재 환불·배송 UNIQUE를 미리 제거하거나 동작하지 않는 선택 파라미터를 늘리지 않는다. S6 대표 전체 플로우·S7 이동·S8 실사/화면의 완료 조건은 기존 계획을 따른다.

## 8. S1 검증 범위

공통 JSON v1/v2·ID/유형·SKU, Spring HTTP/서비스 관리자 검사·상품 소유권, 임시 PostgreSQL의 공식 주체·상품/주문 SQL 제약, 기존 입점 snapshot·회귀와 직매입 Redis/결제/배송/정산 차단을 검증한다. 실행 로그·JUnit XML·기준 소스 SHA-256은 `.workflow/results/EC-INVENTORY-EXPANSION-20261020-S1/`에 보존한다. 구현 검증과 다음 별도 독립 리뷰를 구분한다.
