# 직매입 재고 예약 — S3 업무·실행 계약

Task ID: EC-INVENTORY-EXPANSION-20261020-S3
갱신일: 2026-10-27 (Asia/Seoul)

이 문서는 S3 완료 당시 계약입니다. S5는 별도 `/cancel`과 `CANCELED`로 피킹 전 할당 취소를 추가했습니다. 일반 release의 ALLOCATED 거절은 유지합니다. 현재 결제·취소 경계는 [S5 계약](mixed-payments-s5.md)을 따릅니다.

S2의 창고·입고·검수에 내부 예약·조회·해제·만료·할당 API를 추가한다. 기존 입점 Redis와 연결하지 않는다. 주문 서비스의 실제 원격 조율은 S4, 결제 연동은 S5, 출고·할당 취소는 S6다. 직매입 고객 구매는 계속 차단한다.

## 인증과 실행

`INVENTORY_SERVICE_TOKEN`을 재고 서비스에 안전하게 주입한다. `/internal/reservations/**`는 이 값의 Bearer 인증만 허용하며 상수 시간 비교한다. 미설정/공백이면 403으로 차단한다. 설정된 경우 누락·잘못된 토큰·관리자/사용자 JWT·refresh 토큰은 401이다. 서비스 메서드도 별도 `INVENTORY_SERVICE` 권한을 요구한다. 관리자 API는 기존 ADMIN access JWT를 유지하며 내부 토큰을 인정하지 않는다. 권한 헤더는 신뢰하지 않는다.

Gateway에 internal 라우트를 추가하지 않는다. Compose의 inventory-service는 내부 네트워크에만 있고 포트를 공개하지 않는다. 관리 Swagger의 내부 API 인증은 `inventoryServiceToken`을 선택한다. S3에서는 order-service에 토큰이나 클라이언트를 추가하지 않는다. S4가 같은 전용 값을 안전하게 주입받아 사용할 예정이다.

실제 토큰은 소스·.env·문서·로그·결과 자료에 저장하지 않는다. 운영자가 비밀 관리 경로에서 충분히 무작위인 전용 값을 발급·주입한다. 관리자 JWT/PG 시뮬레이션 토큰을 재사용하지 않는다. 현재 단일 토큰 설정이므로 회전 시 두 서비스의 값을 맞춰 재시작하며 일시 인증 실패 동안 영속 미확인 결과를 보존한다. 외부 네트워크 연결은 TLS가 필요하다. 기존 DB/볼륨 초기화나 운영 SQL 적용은 이 실행 지침의 범위가 아니다.

기존 S2 실행·전용 DB 정책은 [재고 서비스 가이드](inventory-service-s2.md)를 따른다. Flyway V22가 기존 S2 스키마에 예약·확보 기록과 원장 증분을 추가하며 Hibernate validate를 유지한다. 기본 만료 스케줄러는 5초마다 최대 100개를 처리한다. `inventory.reservations.expiry-delay-ms`로 간격을 바꿀 수 있고 테스트에서는 `inventory.reservations.expiry-enabled=false`로 비활성화한다. 조회·재전송·할당도 잠금 아래 TTL을 검사하므로 배치 성공을 전제로 유효한 예약을 판단하지 않는다.

## API와 원본 입력

| 내부 경로 | 동작 |
|---|---|
| PUT /internal/reservations/{id} | 멱등 전량 예약 |
| GET /internal/reservations/{id} | 현재 영속 상태와 로트 확보 기록 조회, 필요한 TTL 만료 처리 |
| POST /internal/reservations/{id}/release | 원본 입력과 일치하는 미할당 예약 해제. 생성 전에도 종결 기록 저장 |
| POST /internal/reservations/{id}/allocate | 원본 입력과 일치하는 유효 예약을 결제 후 할당으로 전환 |

PUT/release/allocate는 모두 `{orderAttemptId, orderItemId, skuId, quantity, expiresAt}`를 받는다. ID는 UUID, quantity는 양의 Long, expiresAt은 UTC ISO instant다. 시각은 마이크로초로 절삭해 원본 비교·DB·응답·Outbox 정밀도를 맞춘다. ID·SKU·수량·만료를 요청마다 바꾸지 않는다. 원본 시각은 1900년 이상 9999년 마지막 초 미만이다.

새 예약의 유효시간 상한은 서버 현재 시각부터 15분이다. 이미 만료된 원본은 EXPIRED 종결 결과를 저장하고 재고를 잡지 않는다. TTL 연장 API는 없다. 동일 ID 재전송은 원본을 비교한 후 현재 결과를 반환하므로 TTL이 지났다고 새 예약을 만들거나 최초 유효시간을 연장하지 않는다. 클라이언트가 요청마다 새 expiresAt을 계산하면 409다.

응답은 `{reservation, lines}`다. reservation에 원본 식별자·수량·expiresAt·상태·선택 warehouseId·reason·최초/변경 시각을, lines에 reservationId·SKU·창고·lotId·quantity를 제공한다. 비교용 requestContent는 노출하지 않는다. lines는 해제 이후에도 확보 이력으로 남으며 살아 있는 예약량으로 해석하지 않는다.

업무 결과는 HTTP 200의 상태로 판정한다. 형식/양수/유효시간 상한 위반 400, 미인증 401, 미설정/권한 부족 403, 조회/할당 대상 없음 404, 같은 ID 다른 원본 및 ALLOCATED 직접 해제 409다. DB/서버 장애와 네트워크 타임아웃은 확정 품절이 아니다.

## 상태·수량·선택

- RESERVED: 한 창고에서 요청 전량 확보했다. 같은 창고 여러 로트를 FEFO(사용기한, 같은 날짜는 로트 ID)로 선택한다. 창고는 priority 오름차순·안정적인 ID 순이며 전량 확보 가능한 첫 창고를 고른다.
- REJECTED: `SKU_UNAVAILABLE` 또는 `NO_SINGLE_WAREHOUSE_STOCK`. 후자는 전체 합계가 충분해도 한 창고에서 전량을 확보하지 못한 결과를 포함한다. 일부 수량 예약·창고 간 분할·다른 SKU 대체는 하지 않는다. 재입고 이후 같은 ID로 성공 상태를 만들지 않는다.
- RELEASED: 확보량을 해제했거나 생성보다 먼저 해제가 도착해 종결했다. 아직 등록되지 않은 SKU도 원본 종결 기록을 남긴다. 늦은 생성·할당은 상태를 되살리지 않는다.
- EXPIRED: `expiresAt <= 서버 현재 시각`. RESERVED 수량만 해제한다. 조회·할당·해제·예약 재전송과 영속 스케줄러에서 동일하게 처리한다.
- ALLOCATED: RESERVED를 같은 로트의 allocated로 전환한다. onHand를 줄이지 않으며 이전 예약 TTL로 자동 해제하지 않는다. 직접 release는 409다. S6의 피킹/출고 경합을 보호하는 창고 취소 절차가 있어야 해제할 수 있다.
- INVALIDATED: 할당 시 SKU/창고 운영·예약 허용·품질·사용기한 조건을 재검사해 부적합 예약만 해제했다. reason은 `STOCK_INELIGIBLE`다. 다른 로트로 조용히 교체하지 않으며 S5가 이 종결 실패를 품목 취소·환불로 보상한다.

PENDING은 요청 선점 트랜잭션 내부 상태이며 정상 커밋 결과로 반환하지 않는다. 일단 종결한 ID는 재활성화하지 않는다. 미할당 RESERVED만 TTL 대상이다. 사용기한은 Asia/Seoul 오늘보다 뒤여야 하며 당일·과거는 제외한다. 정상 GOOD·활성 SKU·활성/예약 허용 창고만 신규 확보·할당할 수 있다. S2의 사용기한과 품질은 변경 API가 없는 불변 로트이며 검수는 별도 품질 로트로 이동한다. 향후 품질/운영 변경도 SKU 잠금 순서를 따라야 한다.

S6의 출고는 할당했던 로트의 품질·사용기한·운영 조건을 다시 검사해야 한다. 할당 이후 부적합해져도 allocated를 TTL로 지우거나 실물을 자동 재입고하지 않는다. 출고를 중단하고 명시적 창고 취소·보상으로 처리한다. 현재는 출고 API를 제공하지 않는다.

## 트랜잭션과 복구

예약 ID 선점/행 잠금 → SKU 행 잠금 → 로트 잔량 잠금 순이다. S2 입고·검수·상품 consumer도 SKU 잠금을 사용하므로 기존 쓰기와 직렬화한다. 한 창고의 전량 확보가 결정된 뒤에만 수량을 변경한다. 후보 합계를 Long으로 더하지 않고 남은 요청량에서 차감해 합산 오버플로도 피한다. DB의 양수·비음수·보유량 상한과 확보 기록의 예약/SKU/창고/로트 복합 FK를 유지한다.

예약 상태·로트 확보 기록·reserved/allocated 잔량·원장·Outbox는 checked 예외까지 한 트랜잭션으로 커밋/롤백한다. 해제·할당·만료 경합도 같은 예약을 잠근 뒤 현재 상태를 재검사한다. 실물 onHand는 바꾸지 않는다. 원장은 S2의 입고 참조를 보존하고 RESERVATION/ALLOCATION/RELEASE/EXPIRATION/INVALIDATION의 예약 ID·로트·reservedDelta/allocatedDelta를 추가한다. 내부 서비스 원장은 actorId/receiptId/receiptLineId가 null이며 주문 시도/품목의 원본은 예약에 남는다.

Outbox는 기존 direct-inventory-events만 사용한다. schemaVersion=1, aggregateType=INVENTORY_RESERVATION, aggregateId=reservationId, eventType=INVENTORY_상태다. 같은 상태의 단순 재전송은 원장·Outbox를 늘리지 않는다. DB에 모든 상태를 남기므로 재시작 시 예약 결과를 조회할 수 있고 만료 스케줄러는 기존 DB의 기한 지난 RESERVED를 다시 찾는다. 실패한 개별 만료는 다음 주기에 재시도하며 로그에는 예약 ID만 남긴다. 종결 레코드 자동 삭제는 구현하지 않는다.

S4 호출자는 원격 부작용 전에 주문 시도·품목·동일 예약 ID·원본을 저장해야 한다. 호출 정책은 연결 1초·응답 2초, 즉시 시도는 최초 포함 최대 3회(재시도 간격 250ms/500ms)로 제한한다. 이 수치는 S3 서버의 원격 클라이언트 구현을 뜻하지 않으며 S4 구현 계약이다. 3회로 결과를 확정하지 못하면 영속 미확인 상태를 남기고 다음 복구 주기로 넘긴다. 취소/만료된 주문은 같은 원본으로 release/GET을 반복해 종결을 확인한다. DB 잠금 상태에서 HTTP를 기다리지 않고 타임아웃을 품절로 표시하거나 새 ID로 재예약하지 않는다.

## 검증

실제 Testcontainers PostgreSQL의 빈 DB 마이그레이션/Hibernate validate와 Spring HTTP API, FEFO·전량/창고 분할 거부·품질/날짜·동시 예약·선행 해제·응답 유실 후 조회·원본 충돌·해제/만료/할당 경합·Outbox 및 checked 예외 롤백·새 애플리케이션 컨텍스트의 DB 복구를 검증한다. 기존 S2 테스트도 변경된 원장·마이그레이션·인증을 대상으로 재검증한다. 최종 실행 근거와 한계는 `.workflow/results/EC-INVENTORY-EXPANSION-20261020-S3/`에 보존하며 독립 리뷰는 별도 세션이다.
