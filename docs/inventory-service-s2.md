# 직매입 재고 서비스 — S2 실행·업무 계약

Task ID: EC-INVENTORY-EXPANSION-20261020-S2
갱신일: 2026-10-27 (Asia/Seoul)

## 현재 제공 범위

inventory-service(8088)는 별도 PostgreSQL inventory_service DB에서 창고·공급사·직매입 SKU·발주·분할 입고·검수·로트·잔량·원장·Outbox를 관리한다. 별도 WMS·Redis·결제·입점 정산을 추가하지 않는다. 직매입 구매는 S1의 차단을 유지한다. S3 내부 예약 API는 [예약 계약](inventory-reservations-s3.md)을, 혼합 주문은 S4 이후 범위를 따른다.

상품 ID=SKU ID다. 별도 상품↔SKU 매핑은 만들지 않으며 inventory_sku는 PRODUCT_REGISTERED/UPDATED/DEACTIVATED의 직매입 전용 로컬 투영이다. ADMIN도 임의 UUID나 입점 상품을 재고 SKU로 등록할 수 없다. product-events의 독립 consumer group(inventory-service-group)을 사용하며 등록이 도착해야 발주할 수 있다. 정보 버전 역전·중복 등록은 이름이나 비활성 상태를 되돌리지 않는다. 선행 등록 누락은 재시도/DLT 대상으로 남긴다. 기존 상품 이벤트·입점 Redis·Product DB 잔량은 변경하지 않는다.

## 인증과 접근

외부 경로는 /api/inventory/** → Gateway의 StripPrefix=1 → /inventory/**다. Gateway는 기존 access JWT·블랙리스트 검사를 유지하며 Authorization을 전달한다. inventory-service도 JWT 서명·만료·access 유형·UUID·ADMIN 역할을 직접 검증한다. X-User-Id/X-User-Role는 작업자나 권한 근거로 사용하지 않는다. 서비스 메서드도 ADMIN을 검사한다.

현재 모든 창고 작업은 ADMIN만 허용한다. 창고별 작업자 역할·허용 창고 ID 발급이 없어 SELLER·USER·공급사·헤더 위조로 우회할 수 없다. S3의 /internal/reservations/**는 별도 INVENTORY_SERVICE_TOKEN만 받고 ADMIN JWT를 거부한다. 미설정이면 차단하며 Gateway에는 내부 라우트를 만들지 않는다. 상세 인증·상태·실행은 예약 계약을 따른다.

Compose에서 서비스 포트를 공개하지 않는다. 로컬 직접 실행도 격리된 개발 환경에서만 사용한다. 서비스 직접 검증은 JWT의 만료까지 유효하며 중앙 블랙리스트 조회는 Gateway에서 수행한다. 따라서 외부 공개는 Gateway를 경유해야 한다. Swagger는 /api-docs/inventory 또는 격리 로컬의 /v3/api-docs, /swagger-ui/index.html이다.

## API

모든 업무 API는 ADMIN access JWT가 필요하다. UUID를 지정하는 PUT은 같은 UUID·같은 내용에 한 번만 효과를 발생시키고 다른 내용은 409를 반환한다. 최초 작업자·시각·업무 참조를 유지한다. 발주·입고의 줄 순서는 의미가 없으며 서버가 정렬해 비교한다. 내부 비교용 본문은 응답에 노출하지 않는다.

| 서비스 경로 | 업무 |
|---|---|
| POST /inventory/warehouses | {code,name,address,active=true,acceptsReservations=true,priority=0} 창고 등록. code 고유. |
| GET /inventory/warehouses | 창고 목록. page/size. |
| POST /inventory/suppliers | {code,name} 공급사 등록. code 고유. |
| GET /inventory/suppliers | 공급사 목록. page/size. |
| GET /inventory/skus | 상품 이벤트로 확인된 직매입 SKU 목록. page/size. |
| PUT /inventory/purchase-orders/{id} | {supplierId,warehouseId,lines:[{skuId,quantity,unitCost}]} 수동 발주. |
| GET /inventory/purchase-orders/{id} | 공급사·창고·작업자·발주 품목·단가·입고 누적·상태 조회. |
| PUT /inventory/receipts/{id} | {purchaseOrderId,reason,lines:[{purchaseLineId,lotCode,expiresOn,quality,quantity}]} 실제 도착·입고 확정. |
| GET /inventory/receipts/{id} | 원 입고 문서·품목·로트·공급사·매입 단가/금액 조회. |
| PUT /inventory/inspections/{id} | {receiptLineId,goodQuantity,damagedQuantity,reason} 검수 중 입고 품목 전량의 정상·불량 확정. |
| GET /inventory/inspections/{id} | 원 입고 품목·검수 수량·작업자·시각·사유 조회. |
| GET /inventory/stock?skuId=...&warehouseId=... | 창고·로트별 onHand/reserved/allocated/blocked/transferHeld/available. warehouseId 생략 가능. page/size. |
| GET /inventory/stock/summary?skuId=...&warehouseId=... | 전체 로트의 SKU·창고별 합산. warehouseId 생략 가능. |
| GET /inventory/ledger?skuId=... | 수량 변화·문서 종류/ID·입고 품목·작업자·사유·시각. page/size. |

page 기본 0, size 기본 50, 상한 200이다. 발주/입고는 1~100줄, 발주는 SKU 중복을 거부한다. 수량·매입단가는 양의 정수 Long, 금액은 정수 원 단위다. 예약/할당 수량은 S3 내부 API에서 변경하며 실물 보유와 구분한다. 입력 형식·범위·오버플로는 400, 미등록 업무 참조는 404, ID 내용 충돌·초과 입고·기존 로트 사용기한 충돌은 409, 미인증은 401, 권한 부족은 403이다. 일시적 서버/DB 장애 응답에서는 새 UUID로 다시 입고하지 말고 같은 UUID로 조회·재시도한다.

## 수량·로트·원가 규칙

발주 예정량은 실제 재고가 아니다. 발주는 OPEN → PARTIALLY_RECEIVED → RECEIVED로 조회되며 입고된 모든 품질의 도착 수량을 누적한다. 100개 발주에 정상 90·불량 3·검수 중 2개 도착은 누적 95, 잔여 5다. 초과 입고는 승인 기능 없이 항상 거부한다. 입고 시 단가를 다시 받지 않고 발주 단가와 매입금액을 고정한다. 불량품의 공급사 비용 조정·지급·반품 회계는 구현하지 않는다.

로트 식별은 (skuId,supplierId,lotCode,quality)다. 품질 GOOD/DAMAGED/INSPECTION 별로 재고를 분리한다. 같은 물리 코드의 품질별 로트는 서로 다른 ID이며 각 로트의 사용기한은 불변이다. 같은 로트는 여러 창고에서 독립 잔량을 가진다. warehouseId+lotId 잔량은 고유하며 SKU 불일치를 DB FK가 거부한다. 입고/검수 참조는 원 문서와 원장에 보존한다.

onHand는 정상·불량·검수 중 전체 보유다. blocked는 불량/검수 중이며 reserved/allocated와 중복하지 않는다. S7의 transferHeld는 이동 출발 전 확보량으로 고객 예약·할당과 분리한다. onHand - reserved - allocated - blocked - transferHeld가 산술 가용량이다. 실제 available은 GOOD, 활성 SKU, 활성·예약 허용 창고, expiresOn > Asia/Seoul 오늘을 모두 만족할 때만 산술 가용량이다. 사용기한 당일·과거 로트는 판매 가능량 0이며 원 보유·원장을 지우지 않는다. 과거 사용기한 입고도 이력으로 허용한다. 날짜는 1900~9999년을 받는다. 저장·합산 수량 및 단가×수량·금액 합산은 Long 범위 초과를 거부한다.

검수 중 입고 품목은 한 번에 전량 검사하며 정상+불량=원 입고 수량이어야 한다. 같은 입고 품목을 다른 검수 ID로 다시 확정하면 409다. INSPECTION 로트 잔량을 줄이고 같은 코드·사용기한의 GOOD/DAMAGED 로트로 이동하므로 총 onHand·발주 누적·원 매입금액은 유지된다. 검수 확정 문서·원장·Outbox가 함께 커밋된다. 사용기한이나 SKU 상태 때문에 판매 불가한 물량은 정상 판정 후에도 판매 가능량에 포함하지 않는다.

## 트랜잭션·이벤트

입고는 발주 행 잠금 → 입고 요청 ID 선점 → 대상 SKU ID 오름차순 잠금 → 로트/잔량 적용 순서다. 발주 행을 먼저 잠가 자식 입고 FK 잠금의 승격 교착을 피한다. 같은 SKU의 여러 발주/창고 입고도 SKU 행에서 직렬화해 신규 로트·잔량의 부재 경합을 보호한다. 로컬 DB 트랜잭션 안에서 원격 호출을 하지 않는다. 발주 잔량·입고/검수 문서·로트·잔량·원장·Outbox는 checked 직렬화 예외까지 함께 롤백한다.

원장은 INBOUND/INSPECTION의 documentId와 원 receiptId/receiptLineId, SKU·창고·로트별 onHandDelta/blockedDelta를 보존한다. 입고 재전송·검수 재전송은 원장·매입금액을 늘리지 않는다. 검사 원장의 출발/도착 변화 합계는 onHand 0이고 정상 판정 수량만 blocked를 줄인다.

신규 Outbox는 direct-inventory-events에 INVENTORY_RECEIVED/INVENTORY_INSPECTED, payload schemaVersion=1을 발행한다. 기존 PRODUCT_STOCK_UPDATED나 주문 재고 이벤트와 연결하지 않는다. 공통 Outbox 전송·재시도·DLT 보관을 재사용한다. 이 단계의 검증은 Outbox 저장과 상품 consumer의 실제 DB 처리를 확인하며 Kafka 실제 전달·재균형을 실행한 결과로 해석하지 않는다.

## 로컬 실행

JDK 21, 기존 Gradle wrapper와 PostgreSQL 16·Kafka 로컬 환경을 사용한다. inventory_service DB를 전용으로 준비해야 한다. 새 Compose 볼륨은 scripts/init-databases.sql에서 DB를 만들지만 기존 PostgreSQL 볼륨에는 초기화 스크립트가 다시 실행되지 않는다. 기존 DB를 초기화하지 말고 전용 DB 추가 권한을 확인한 뒤 준비한다. 이번 개발은 테스트 전용 임시 DB 외에 SQL을 적용하지 않았다.

새 서비스는 기본/dev/docker/prod 모두 Hibernate validate + Flyway를 사용한다. 빈 전용 DB는 V1의 완전한 기준선·공통 V10~V21·S3 V22로 구성된다. Flyway clean·baseline-on-migrate는 비활성이다. 기존 서비스의 dev/docker update 정책은 변경하지 않는다. 이미 데이터가 있는 임의 DB를 신규 서비스용으로 사용하지 않는다.

JWT_SECRET은 기존 신뢰할 수 있는 개발용 값, INVENTORY_DB_URL/DB_USERNAME/DB_PASSWORD/KAFKA_BOOTSTRAP_SERVERS는 전용 개발 환경에서 안전하게 주입한다. 실제 값은 명령 기록·문서·결과에 출력하지 않는다. 기존 .env를 불필요하게 열거나 새 인증정보를 저장하지 않는다.

```bash
# 전용 로컬 DB·Kafka와 환경변수를 준비한 후
./gradlew :inventory-service:bootRun

# 기존 격리 Compose 환경에서 새 서비스만 로컬 소스로 빌드·시작
# 기존 볼륨이면 inventory_service DB의 준비 여부를 먼저 확인한다.
docker compose up -d --build inventory-service

# 현재 소스의 서비스 JAR 빌드
./gradlew :inventory-service:build

# Testcontainers 전용 DB + 실제 HTTP 서버·MockMvc + consumer 처리 검증
./gradlew :inventory-service:test
```

Compose의 재고 이미지는 jybeomss1/inventory-service:latest이며 전체/단일 build·push·pull 스크립트가 지원한다. `bash ./build-and-push.sh && bash ./pull-and-start.sh`로 9개 서비스를 함께 준비하며, 전체 pull은 누락된 내부 토큰과 inventory_service DB만 추가한다. 기존 DB/볼륨을 삭제하거나 스키마를 자동 이전하지 않는다. Gateway도 새 라우팅 설정이 포함된 현재 소스로 재빌드해야 한다. 기존 latest 이미지에 새 라우트가 들어 있다고 가정하지 않는다. 실제 자격 증명·Compose 전체 서비스·외부 PG/메일/Telegram을 사용한 통합 구매 검증은 S6로 남긴다.

## 검증과 인계

실제 임시 PostgreSQL에서 마이그레이션/Hibernate validate, 빈 DB·Swagger·HTTP 기동, 100/95 분할 입고·품질·사용기한·두 창고·중복·경합·수량/원가·오버플로·DB 제약·동반 롤백·검수 재분류·JWT 우회를 검증한다. 테스트 자격 증명은 실행 중 생성된 임시 값이다. 자료는 .workflow/results/EC-INVENTORY-EXPANSION-20261020-S2/에 저장한다. 개발 검증 후 별도 workflow-independent-review가 끝나야 S2 완료다.

## S1~S8 통합 리뷰 반영 — 정수 입력

발주 수량·매입단가, 입고/검수, 내부 예약, 출고/반품, 이동/도착, 실사 수량은 필수 JSON 정수 토큰만 받는다. 누락/null·소수·지수 표기·문자열·boolean·배열·객체·Long 범위 초과는 400이며 서비스 쓰기 전에 거부한다. 기존 양수/비음수 규칙은 유지한다. 전역 Jackson 정책과 다른 서비스 API는 바꾸지 않는다.
