# S7 창고 간 이동·미도착 복구 계약

Task ID: EC-INVENTORY-EXPANSION-20261020-S7

## 수량과 처리 경계

이동 요청은 출발 창고의 명시한 GOOD 로트 1~100줄을 전량 확보한다. 각 줄의 lotId는 중복할 수 없고 quantity는 양의 Long이다. 같은 창고 이동, 비활성 창고의 새 요청, 미확보 잔량 초과를 거부한다. `transferHeld`는 고객 reserved/allocated/blocked와 별도이며 산술 가용량은 `onHand - reserved - allocated - blocked - transferHeld`다. 기존 예약의 후보 SQL·로트 선택과 로트/창고 요약에 모두 적용한다.

상태는 HELD → IN_TRANSIT → PARTIALLY_RECEIVED → RECEIVED이며 부분 도착 없이 전량 도착도 가능하다. HELD에서만 CANCELED로 취소할 수 있다. 확보는 onHand를 보존하고, 출발은 출발 창고의 onHand와 transferHeld를 함께 차감한다. 도착은 실제 도착한 로트 수량만 상대 창고 onHand에 더한다. 같은 로트가 도착 전 양쪽 창고에서 동시에 판매되지 않는다. `출발 보유 + 도착 보유 + 이동 중 = 이동 전 총보유`를 검증한다. 기존 고객 예약·출고 등 독립 수량 변화는 별도 원장으로 구분한다.

물리 이동은 판매와 구분한다. GOOD 로트는 사용기한 만료나 SKU 비활성에도 옮길 수 있다. 도착 시에도 상태를 이유로 실물을 누락하거나 만료일을 바꾸지 않는다. 기존 품질·기한·SKU·창고 조건으로 실제 available이 0일 수 있다. INSPECTION/DAMAGED 이동은 초기 범위에서 거부하며 검수 중 입고 문서의 원 창고 정합성을 유지한다. 창고의 acceptsReservations는 신규 고객 예약 조건이며 창고 이동 자체의 조건이 아니다.

로트 ID·SKU·공급사·코드·품질·사용기한은 그대로 유지하고 새 발주/매입/입고 금액을 생성하지 않는다. 원 입고 receiptLineId와 purchaseLineId·unitCost·purchaseAmount는 기존 입고 문서·원장에 보존된다. 검수로 파생된 GOOD 로트는 동일 SKU·공급사·코드·기한의 원 INSPECTION 로트와 INSPECTION 원장을 통해 원 매입을 추적한다. 이동은 기존 매입원가를 재계산하거나 특정 매입 단가를 새로 선택하는 업무가 아니다.

## 관리자 API

직접 경로는 `/inventory/transfers`, Gateway 경로는 `/api/inventory/transfers`다. 모든 HTTP와 서비스 진입점은 ADMIN access JWT를 요구한다. 내부 주문 서비스 토큰·USER·SELLER·역할 헤더는 관리자 권한을 부여하지 않는다. 인증 실패 401, 인증된 비관리자 403, 입력/산술 범위 400, 없는 참조 404, 원본 충돌·수량 부족·허용하지 않는 상태 전이 409다.

| 메서드·경로 | 내용 |
|---|---|
| PUT /{id} | sourceWarehouseId, destinationWarehouseId, expectedArrivalAt, reason, lines[{lotId,quantity}]. UUID별 최초 확보. |
| GET /{id} | 이동 문서와 로트별 원본 수량·누적 receivedQuantity·heldQuantity·inTransitQuantity. |
| PUT /{id}/dispatch | reason을 받아 전량 출발. 출발을 여러 부분으로 나누지는 않는다. |
| PUT /{id}/cancellation | reason을 받아 출발 전 확보만 해제. 출발 후에는 409. |
| PUT /{id}/receipts/{receiptId} | reason, lines[{lotId,quantity}]의 실제 부분/전체 도착. 누적 도착이 이동량을 넘으면 전체 요청 롤백. |
| GET /{id}/receipts | 도착 문서 페이지 조회. |
| GET /{id}/receipts/{receiptId} | 특정 도착 원본과 로트별 수량. 다른 이동의 도착 ID는 404. |
| GET ?warehouseId=&incompleteOnly=&overdueOnly=&page=&size= | 출발/도착 창고별 미완료·기한 초과 조회. |

page 기본 0, size 기본 50, 상한 200이다. 미완료는 HELD/IN_TRANSIT/PARTIALLY_RECEIVED이며 overdueOnly는 expectedArrivalAt ≤ 현재인 미완료만 반환한다. 누락을 숨기는 자동 완료·분실 처리나 수량 원복은 없다. 운영자가 실물을 확인해 같은 출발/도착 요청을 재처리한다. 새 요청의 도착 예정은 현재 이후이고 1900~9999 범위다. 이미 생성한 이동은 예정 시각 경과 후에도 같은 ID·본문으로 재조회/재시도할 수 있다.

이동 생성과 도착은 줄 순서를 정규화하며 같은 ID의 다른 본문은 409다. 출발·취소는 이동 ID+동작이 멱등 식별자이고 reason 원본까지 비교한다. 도착 ID는 전체 이동에서 고유하므로 다른 이동으로 재사용할 수 없다. 응답 유실 시 새 ID를 만들지 않고 GET으로 확인하거나 같은 ID·본문으로 다시 보낸다. CANCELED의 같은 생성 요청도 종결 상태를 반환하며 되살리지 않는다.

## 잠금·원자성·초기 스키마

이동 문서 행 → SKU ID 오름차순 → 잔량 순서로 잠근다. 이동 간·고객 예약·입고·반품과 동일 SKU 잠금을 공유해 중복 확보와 신규 도착 잔량의 부재 경쟁을 보호한다. 한 이동의 모든 상태 전이는 같은 행 잠금으로 직렬화한다. 외부 서비스 호출을 DB 트랜잭션 안에서 수행하지 않는다.

문서·도착 문서/줄·잔량·TRANSFER_HOLD/CANCEL/DISPATCH/ARRIVAL 원장·Outbox는 checked JSON 예외까지 동반 롤백한다. 원장에는 transferHeldDelta와 실물 변화, 작업자·사유·원인 이동/도착 ID를 남긴다. Outbox는 기존 direct-inventory-events 경로에 schemaVersion 1의 INVENTORY_TRANSFER를 기록한다. 기존 주문/배송 소비자는 자기 업무 이벤트만 처리하므로 이동을 고객 배송/환불로 해석하지 않는다.

배포 전 초기 개발 원칙에 따라 잔량 transfer_held·불변조건과 원장 transfer_held_delta는 V1의 CREATE TABLE 정의에 포함한다. 최종 원장 유형 제약은 기존 V24에 포함하며, 신규 이동 테이블의 최초 생성은 V25__warehouse_transfers.sql이다. 예전에 삭제한 S6 회수 증분 V25와 다른 신규 기능 파일이다. 빈 전용 DB에서 Flyway 전체 생성·Hibernate validate·CHECK/FK/UNIQUE를 검증한다. 기존 DB checksum repair·데이터 이전·볼륨 초기화는 수행하지 않는다.

## 실행과 검증 범위

JDK 21·Docker에서 이동의 실제 PostgreSQL/HTTP·경합·재시작·실패 회귀와 재고 빌드를 실행한다.

```bash
./gradlew :inventory-service:test :inventory-service:build --offline --no-daemon --console=plain --max-workers=2
```

이동만 선택하려면 `:inventory-service:test --tests com.eventfulcommerce.inventory.TransferIntegrationTest`를 사용한다. 테스트는 Testcontainers의 새 PostgreSQL과 생성한 JWT/서비스 토큰만 사용한다. Kafka listener/Outbox publisher를 끄고 DB의 발행 대기를 검증한다. Spring 컨텍스트 재기동으로 같은 DB의 미출발·부분 도착을 조회하고 동일 도착 ID 재전송과 남은 실물 도착을 확인한다. 강제 프로세스 종료나 외부 장비 검증은 아니다.

기존 대표 구매 명령에는 S6 구매/반품 이후 S7의 5개 이동·2개 부분 도착·3개 잔여 도착·응답 재전송·출발 후 취소/초과 도착 거부·실제 재고 서비스 재기동을 추가했다.

```bash
python3 scripts/test-11-mixed-fulfillment.py
```

이 명령은 S6와 동일하게 매번 새 Docker 환경·fixture를 만들고 민감정보를 제거한 HTTP·원장·기대/실제 결과를 scripts/results/에 남긴다. 이번 S7 개발에서 실제 실행한 명령과 결과는 .workflow/results/EC-INVENTORY-EXPANSION-20261020-S7/validation.json을 따른다. 스크립트 수정이나 과거 S6 성공을 현재 S7 포함 전체 Kafka 실행 성공으로 간주하지 않는다.

S8 실사·관리자 화면은 [S8 가이드](stock-counts-admin-s8.md)를 따른다. 이동 중 분실 확정/손실 조정·실물 회송 자동화, 불량/검수 중 로트 이동, 이동 자동 배차·실제 택배·외부/운영 적용은 후속 범위다. 출발 후 취소는 원 창고 가용량을 복원하는 명령으로 제공하지 않는다.
