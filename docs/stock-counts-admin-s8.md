# S8 실사·대사·창고 운영 화면

Task ID: EC-INVENTORY-EXPANSION-20261020-S8
상태: 구현·필수 검증 완료 / 별도 독립 리뷰 대기

## 관리자 화면 실행

기존 로컬 실행 구성에서 inventory-service와 Gateway를 **현재 소스**로 빌드·기동한 뒤 다음 주소를 연다. 운영/공유 DB나 기존 개발 DB를 이 작업에서 이전·초기화하지 않는다. 신규 전용 빈 DB의 준비와 필수 JWT 설정은 [S2 가이드](inventory-service-s2.md)를 따른다.

- Gateway: `http://localhost:8080/api/inventory/admin/index.html`
- 재고 서비스 직접 접근: `http://localhost:8088/inventory/admin/index.html`

현재 프로젝트에는 USER/SELLER/ADMIN만 있으므로 기존 ADMIN access JWT를 사용한다. 별도 창고 역할이나 자동 관리자 발급 기능은 추가하지 않는다. 토큰을 URL에 넣지 말고 화면의 비밀번호 입력란에 입력한다. 화면은 API의 ADMIN 확인이 성공한 후 작업 영역을 연다. 토큰은 메모리에만 보관하며 localStorage/sessionStorage/cookie에 쓰지 않는다. 연결 종료·401/403 시 보유 토큰과 조회 결과를 지운다. 페이지를 새로 열면 다시 연결한다. 원격 사용은 HTTPS를 사용하며 localhost는 브라우저의 보안 컨텍스트 예외를 사용한다.

공개 경로는 정확히 위 HTML과 같은 디렉터리의 app.js/styles.css **GET 세 개**다. 이는 데이터 없는 연결 화면과 자산이며 API의 ADMIN 요구를 완화하지 않는다. Gateway는 기존 JWT·폐기 토큰 검사를 수행하고, 직접 서비스 접근도 자체 JWT/역할을 검증한다. USER/SELLER/내부 서비스 토큰·refresh 토큰·위조 역할 헤더는 업무 권한이 없다. CSP로 외부 코드/전송·폼 기본 제출을 막고 서버 문자열은 textContent로 표시한다.

화면은 기존 API로 창고/로트 재고와 각 수량, 수동 발주, 입고, 검수, 피킹/포장/실제 출고, 창고 이동/출발/부분 도착, 실사와 원장 대사, 미완료 작업을 제공한다. 재고 행의 조회 버튼은 로트·창고를 실사/이동 입력란에 채운다. 미완료 출고/이동/실사 행의 조회 버튼은 해당 작업을 연다. 발주 조회로 실제 발주 품목을 선택하며 입고 결과의 품목 번호로 검수를 진행한다.

초기 화면의 발주·입고·이동·도착은 한 품목/로트씩 입력한다. 여러 줄 API는 기존 계약을 유지한다. 마스터 자동완성은 첫 200개이며 목록 밖 UUID는 직접 입력할 수 있다. 화면의 수량 입력은 0~Number.MAX_SAFE_INTEGER(양수 작업은 1 이상)로 제한하고 초과 수량은 API를 사용한다. 조회값은 Long 범위를 문자열/BigInt로 보존한다. 폼의 문서 번호는 실패 후 유지하므로 같은 내용으로 재시도할 수 있다. 새 업무는 ‘새 발주/새 입고/새 실사’ 등으로 새 번호를 만든다.

## 실사 정책

한 실사는 기존 창고×로트 잔량 하나를 대상으로 한다. GOOD/DAMAGED 로트의 실사이며 INSPECTION은 원 입고 검수를 마친 뒤 진행한다. 사용기한이 지난 재고도 실물 보유로 실사할 수 있지만 기존 판매 적격성 검사는 유지된다.

1. 기준 기록 시 SKU 잠금과 잔량 잠금 아래 onHand/blocked/version·서버 기준 시각·작업자를 저장한다. reserved/allocated/transferHeld가 하나라도 있으면 409다. 피킹/포장 수량은 allocated에 포함된다. 정상 로트는 blocked=0, 불량 로트는 blocked=onHand인 재고만 허용한다.
2. 실물 수량과 사유를 별도 측정 문서에 기록한다. countedQuantity는 필수 JSON 정수이며 0~9223372036854775807을 허용한다. 생략/null·소수/지수 표기·문자열/boolean/배열/객체·음수·Long 초과는 400이고 문서·재고·원장·Outbox를 바꾸지 않는다. 상태는 OPEN → MEASURED다. 최초 측정은 변경하지 않으며 잘못 입력하면 취소 후 새 실사를 시작한다.
3. 승인 시 실사 문서 → SKU → 잔량 순서로 잠그고 같은 무확보 조건·기준 version/onHand/blocked를 재검사한다. 측정 중 기존 입고/주문/출고/이동을 장시간 차단하지 않는다. 어느 작업이든 해당 잔량 버전을 바꿨으면 수량이 원래 값으로 돌아와도 409로 취소·재실사를 요구한다. 다른 로트의 독립 작업은 이 실사 기준을 무효화하지 않는다.
4. 일치한 기준에서만 차이 `countedQuantity - baseOnHand`를 원장에 기록하고 보유량을 조정한다. 불량은 같은 차이만큼 blocked도 조정해 판매 가능량을 만들지 않는다. 차이 0도 이력이 남는다. APPLIED 상태·조정량·승인자/시각/사유·STOCK_COUNT 원장·Outbox를 같은 트랜잭션으로 커밋한다. DB 실패/checked JSON 실패는 전부 롤백한다.
5. CANCELED/APPLIED는 종결 상태다. 같은 ID·같은 동작 원본은 현재 결과를 반환하며 원본을 바꾸면 409다. 이미 적용한 실사의 취소로 수량을 원복하는 기능은 없다. 새 실사로 실제 수량을 다시 확인한다.

로트 물량을 덮어쓸 때 예약/할당을 0으로 지우지 않는다. 활성 작업 중 발견한 실물 부족은 해당 창고 작업·취소 경로에서 먼저 처리하고 무확보 상태에서 실사한다. 실사는 매입원가·공급사 지급·고객 환불을 새로 만들지 않는다.

## 실사 API

다음 경로는 직접 `/inventory`, Gateway `/api/inventory`를 기준으로 한다. 생성/업무 UUID는 클라이언트가 만들고 응답 유실 시 같은 번호와 본문으로 재시도한다.

| 메서드·경로 | 본문/응답 |
|---|---|
| PUT /counts/{id} | warehouseId, lotId, reason, 선택 reconciliationId. 기준과 OPEN 기록. |
| PUT /counts/{id}/measurement | countedQuantity(필수 JSON 정수, 0~Long.MAX_VALUE), reason. 측정 기록. |
| PUT /counts/{id}/approval | reason. 기준 검사 후 조정 승인. |
| PUT /counts/{id}/cancellation | reason. 미적용 실사 취소. |
| GET /counts/{id} | 기준·측정·조정·각 작업자/시각/사유·상태. |
| GET /counts | 선택 warehouseId, incompleteOnly, page, size. 미완료는 OPEN/MEASURED. |

401 인증 실패, 403 인증된 비관리자, 400 입력/수량/산술/페이지 범위, 404 없는 참조, 409 업무 상태·기준 변경·원본 충돌이다. 문서의 requestContent 등 내부 멱등 본문은 API에 노출하지 않는다.

## 영속 원장 대사와 복구 추적

`PUT /reconciliations/{id}`는 skuId, 선택 warehouseId, reason, page(기본0), size(기본50/최대200), 선택 previousReportId를 받는다. 모든 정상 수량 writer와 같은 SKU 잠금으로 잔량/원장을 일관된 커밋 경계에서 비교한다. 원장의 sum은 PostgreSQL numeric으로 계산해 Long 합산 오버플로를 숨기지 않는다.

FULL OUTER JOIN으로 잔량 행만 있는 경우와 원장만 있는 경우도 포함한다. 각 행에 warehouseId/lotId/balanceId/version, 현재 onHand/reserved/allocated/blocked/transferHeld, 각 ledger 합계, matched를 저장한다. 페이지별 mismatchCount·hasNext·범위·확인자/시각/사유와 결과 자체를 영속화한다. **mismatchCount=0은 해당 페이지의 판정**이다. hasNext면 새 보고서 ID와 다음 page로 계속 확인한다. 같은 대사 ID 재시도는 과거 결과를 반환하고 재측정하지 않는다.

`GET /reconciliations/{id}`로 원본 결과를 보고, `GET /reconciliations?skuId=&warehouseId=&page=&size=`로 이력을 조회한다. 후속 보고서의 previousReportId는 같은 SKU/창고/페이지/크기의 선행 범위만 연결한다. 실사의 reconciliationId는 해당 보고서 페이지에 실제로 포함된 창고·로트만 연결할 수 있다.

대사는 재고를 자동 수정하거나 원장을 삭제·재작성하지 않는다. 실물 차이는 연결한 실사로 확인한다. 이미 존재하는 잔량-원장 손상은 양쪽에 같은 실사 delta를 더해도 해결되지 않으므로 원인 문서·원장부터 조사한다. 데이터 손상 복구는 별도 검토한 대상 작업으로 처리하고 새 후속 보고서로 결과를 남긴다. 이번 구현은 범위 밖 임의 SQL 복구를 수행하지 않는다.

## 운영 조회

`GET /operations/reservations`는 선택 warehouseId/skuId로 PENDING/RESERVED/ALLOCATED를 조회한다. 만료 시각을 함께 보여주되 조회로 예약을 변경하지 않는다. `GET /operations/outbounds`는 READY/PICKING/PACKED와 배송 완료 전 SHIPPED를 창고별 조회한다. `GET /operations/purchase-orders`, `/operations/receipts`는 창고별 발주·입고 원본을 조회한다. 모두 page≥0, size 1~200이다.

이동의 미완료/기한 초과/부분 도착과 동일 ID 재처리는 [S7 계약](warehouse-transfers-s7.md)을 유지한다. 화면에서 상태를 확인하고 원래 업무를 재처리하며 자동 성공 처리나 임의 가용량 복원을 하지 않는다.

## 스키마와 검증

배포 전 초기 개발 정책에 따라 V26__stock_counts_and_reconciliation.sql은 신규 실사·대사 테이블의 최초 정의다. STOCK_COUNT 원장 유형은 최종 원장 제약을 정의하는 기존 V24에 반영했다. 빈 전용 PostgreSQL에서 Flyway 12개와 Hibernate validate·CHECK/FK/UNIQUE를 확인했다. 기존 DB checksum repair/초기화·기존 데이터 이전은 실행하지 않았다.

```bash
./gradlew :inventory-service:test :inventory-service:build :api-gateway:test :api-gateway:build --offline --no-daemon --console=plain --max-workers=2
```

위 명령은 수동 전체 재현용이다. 이번 실행은 새 S8 18건+Gateway9건 성공 후 아직 실행하지 않은 영향 범위인 기존 재고76건과 추가 S8 4건만 실행했으며 성공한27건은 반복하지 않았다. 총 고유107건·실패/오류/생략0건과 두 모듈 build를 확인했다. PostgreSQL/실제 HTTP는 실사 기준 무효화·예약/이동/피킹 보호·동시 승인·롤백·페이지·대사 손상 탐지·새 Spring 컨텍스트 복구·ADMIN 권한을 검증한다. Kafka publisher는 끄고 Outbox 기록을 검증했다.

화면은 설치된 jsdom과 API 응답 fixture로 두 경로 각각33개 요청의 대표 폼/토큰 비저장·거부·만료·XSS 텍스트 처리·Long 정밀도를 검증했다. 추가로 현재 JAR와 새 격리 PostgreSQL을 기동하고 jsdom 화면의 실제 HTTP 26개 요청으로 관리자 연결→발주/정상 입고10개→실사8개→이동2개/부분 도착1+1→두 창고 대사/총보유8개·불일치0건→USER 거부를 검증했다. 수량은 UI 입고로 생성했고 상품 이벤트의 SKU 연결만 fixture SQL로 준비했다. 임시 서버/DB는 정리했다. 실제 브라우저 렌더링/레이아웃·접근성 보조기기는 미검증이다. 테스트 코드·로그·XML·소스 기준선·초기 DOM 환경 오류는 `.workflow/results/EC-INVENTORY-EXPANSION-20261020-S8/`에 보존했다.

기존 `python3 scripts/test-11-mixed-fulfillment.py`에 S7 이동 후 S8의 기준·분실1개 측정·중복 승인·원본 변경409·다섯 차원 대사·원장1건 검증을 추가했다. 이번에는 Python AST 검사만 수행했다. 과거 S6 성공이나 이번 DB/DOM 검증을 수정된 전체 Kafka/Redis 구매 실행 성공으로 확대하지 않는다. 실제 외부 PG/택배/POS·강제 종료/부하·운영/공유 적용·커밋/배포는 수행하지 않았다.

S8은 별도 독립 리뷰의 Major1건(REV-001) 반영까지 완료했다. 실사 수량 필드에만 엄격한 역직렬화를 적용하고 전역 Jackson 정책과 다른 수량 요청은 보존했다. 새 입력16건과 기존 실사22건의 총38건·실패/오류/생략0건 및 재고 build를 같은35초 명령으로 확인했다. 이전107건 성공과 이번38건을 전부 신규 실행이나 고유145건으로 합산하지 않는다. 실제 HTTP 입력 거부 후 전체 스냅샷 불변·승인 거부, 명시적0/8/Long.MAX_VALUE의 정확한 원장/Outbox·멱등을 검증했다.

TASK completed·HANDOFF inactive이며 S1~S8 계획을 완료로 기록했다. 반영 결과는 [S8 RESOLUTION](../reviews/EC-INVENTORY-EXPANSION-20261020-S8/RESOLUTION.md), 새 XML/로그/소스 해시는 S8 결과 apply-review/에 있다. 위 전체 구매/브라우저/외부·운영 적용의 한계는 유지한다.
