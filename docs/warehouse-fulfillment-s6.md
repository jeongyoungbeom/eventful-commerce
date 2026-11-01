# S6 창고 출고·배송·반품 계약

Task ID: EC-INVENTORY-EXPANSION-20261020-S6

## 실제 로컬 전체 구매 실행

저장소 루트에서 다음 명령 하나로 실행한다. Python 3 표준 라이브러리, JDK 21, Gradle wrapper, Docker가 필요하다.

```bash
python3 scripts/test-11-mixed-fulfillment.py
```

현재 소스의 상품·재고·주문·결제·배송·정산·Gateway JAR를 빌드하고 실행마다 별도 Docker 네트워크, PostgreSQL, 3노드 Redis Cluster, Kafka와 7개 서비스 컨테이너를 만든다. 런타임은 기존 Dockerfile과 같은 eclipse-temurin:21-jre-alpine이다. Gateway만 임의 루프백 포트에 공개하며 서비스 간에는 실행 전용 네트워크를 사용한다. 기존 Compose 컨테이너·DB·볼륨·.env는 사용하지 않는다. 실행이 끝나면 생성한 컨테이너/네트워크만 제거한다. 강제 SIGKILL/호스트 종료 시에는 eventful.s6.run 라벨로 남은 자원을 식별할 수 있다.

실행 전용 JWT 키·ADMIN/SELLER/USER 식별자·access JWT·내부 토큰·결제 시뮬레이션 토큰을 생성한다. 실제 계정/비밀정보를 읽지 않는다. 회원가입/로그인 기능은 이 시나리오의 검증 범위가 아니다. 모든 업무 HTTP는 실제 Gateway JWT 검증을 거친다. PG 기본 웹훅의 미구성 503과 시뮬레이션 토큰 누락 401을 확인한 뒤 명시적 dev,payment-simulation 프로필의 보호된 API로 결제한다.

공급사·공식 판매 주체·상품·두 창고·발주·입고·검수 API로 fixture를 생성한다. 샴푸100·크림20을 검수 입고하고, 마스크팩은 만료 GOOD 로트7개만 입고하여 판매 가능량0·주문 제외를 확인한다. 업무 수량을 DB에 직접 넣지 않는다. dev의 기존 Hibernate 초기화 정책을 유지하고 이번 실행의 새 DB에만 주문/결제 증분 제약과 직매입 배송 DDL을 적용한다. inventory-service는 전체 Flyway와 Hibernate validate로 기동한다.

품목은 클렌저 12,000원×1, 샴푸 10,000원×2, 크림 15,000원×1, 품절 마스크팩 1개다. 직매입 배송비 3,000원은 이번 테스트 입력이며 총액은 50,000원이다. 고객 확인 전 예약, 결제 후 할당, 재고 프로세스 재기동 후 피킹/포장/출고, 직매입 배송 2건과 입점 배송/정산 1건, 재고·로트별 원장, 중복 요청, 출고 후 취소 거절과 반품 환불을 단언한다.

결과는 scripts/results/S6-<시각>-<고유 ID>/result.json에 저장한다. 실패 단계·재현 명령·기대/실제 단언·fixture·예약 주문·최종 기준선·현재 JAR 해시·서비스 로그를 보존한다. 인증 헤더는 저장하지 않고 생성한 비밀값은 로그에서 제거한다. 상태 완료는 고정 대기로 가정하지 않고 제한 시간 안에 조회한다. 매번 새 환경/fixture를 만들며 같은 실행 안의 같은 ID 전송만 멱등 검증이다.

## 출고 지시와 재고

주문은 할당 원본을 저장한 트랜잭션에서 DIRECT_FULFILLMENT_REQUESTED를 Outbox에 기록한다. 재고는 같은 예약 잠금 안에서 주문·품목·SKU·수량·창고 원본을 대조한 뒤 지시를 만든다. 취소가 먼저 도착했으면 늦은 지시가 예약을 되살리지 않는다.

초기 출고 지시 한 건은 한 주문 품목 전량이며 ID는 기존 reservationId다. 한 품목이 확보한 한 창고의 여러 FEFO 로트가 그 지시의 로트 목록이다. 플랫폼 SellerOrder 하나에 여러 지시/배송을 연결한다. 같은 창고의 서로 다른 품목을 한 운송장으로 합치는 기능은 제공하지 않는다.

READY → PICKING → PACKED → SHIPPED 순서다. pick/pack/ship은 서버가 확보한 모든 lotId·quantity와 같은 목록 및 사유를 요구한다. 중복 로트·수량 변경·다른 원본 재전송·잘못된 순서는 409다. 같은 작업/같은 원본 재전송은 후속 단계에서도 원래 결과를 보존한다.

피킹·포장은 onHand/allocated를 보존한다. 각 작업과 실제 출고 직전에 SKU/창고 운영 상태·GOOD 품질·Asia/Seoul 사용기한·할당 잔량을 검사한다. 부적격이면 409로 출고를 중단하며 창고 취소로 해제한다. 실제 출고는 onHand와 allocated를 함께 차감하고 예약 SHIPPED·문서·작업자 원장·OUTBOUND_SHIPPED Outbox를 같은 트랜잭션으로 기록한다. 직렬화 오류도 전체 롤백한다.

## 관리자 API

모든 /inventory 경로와 서비스 관리자 메서드는 ADMIN access JWT를 요구한다. X-User-* 헤더나 재고 내부 토큰으로 창고 작업을 할 수 없다. 외부는 Gateway를 경유한다.

| 요청 | 동작 |
|---|---|
| GET /api/inventory/outbounds?orderId=UUID | 주문의 출고 목록, page/size(최대100) |
| GET /api/inventory/outbounds/{reservationId} | 지시·로트별 수량·배송 완료 시각 |
| POST /api/inventory/outbounds/{id}/pick | 로트별 전량 피킹 |
| POST /api/inventory/outbounds/{id}/pack | 로트별 검수·포장 확인 |
| POST /api/inventory/outbounds/{id}/ship | 실제 출고 확정 |
| PUT /api/inventory/outbounds/{id}/cancellation-decision | 창고 취소 승인/거절 |
| PUT /api/inventory/outbounds/{id}/ineligibility-cancellation | 거절 뒤 부적격해진 출고 전 품목의 로트 전량 회수 확인·할당 해제 |
| PUT /api/inventory/returns/{returnId} | 출고 품목 전량 반품 접수 |
| GET /api/inventory/returns/{returnId} | 반품 조회 |
| POST /api/inventory/returns/{returnId}/receive | 실제 수령 기록 |
| PUT /api/inventory/returns/{returnId}/inspection | 원 출고 로트별 정상/불량 검수 및 재입고 |

창고 작업 본문은 `{ "reason": "실물 확인", "lines": [{ "lotId": "UUID", "quantity": 2 }] }`다. 취소 결정은 `{ "approved": true, "reason": "피킹 물품 회수" }`다.

## 취소·환불과 경합

피킹 전 직매입 취소는 예약/할당과 READY 지시를 같은 예약 잠금에서 종결한다. 피킹 이후에는 요청을 영속화하고 창고 결정 전에는 포장·출고와 환불을 보류한다. 기존 주문 품목 취소 API는 202이며 조회의 품목 cancellationStatus는 PENDING이다.

승인은 할당만 해제하고 onHand를 유지한다. 주문의 같은 예약 ID 복구가 CANCELED를 확인한 뒤 기존 S5 품목 환불을 발행한다. 거절은 보존하며 주문 API는 409, 품목 cancellationStatus는 REJECTED다. 다른 품목의 취소·최초 배송비 배분은 보존한다. 출고 이후 단순 취소는 항상 거절하고 재고를 복원하지 않는다.

거절 후 PICKING/PACKED 품목이 사용기한·품질·SKU/창고 운영 조건 때문에 출고 부적격이 되면 관리자가 `ineligibility-cancellation`에 창고 작업과 같은 본문으로 실제 회수한 로트별 전량과 사유를 확인한다. 유효 재고, 다른 로트·수량, 출고 후 요청은 409다. 기존 `cancellation-decision`의 내용·승인 여부는 덮어쓰지 않으며 원 거절 재전송도 보존한다. 별도 회수 원본을 저장하므로 같은 회수 재전송은 중복 효과가 없고 다른 내용은 409다. 회수 확인 전에는 할당·환불을 보류한다.

회수 확인은 같은 예약→SKU 잠금과 트랜잭션에서 예약/출고 CANCELED·allocated 차감·작업자 원장·OUTBOUND_INELIGIBILITY_CANCELED Outbox를 기록하고 onHand는 유지한다. 주문 소비자는 주문·사용자·품목·예약·SKU·창고·수량 원본을 대조해 해당 품목의 기존 S5 환불을 한 번 요청한다. 기존 거절 flag는 현재 품목 상태에서 해제하며 과거 창고 거절 이력은 재고 DB에 보존한다. 취소 HTTP 복구가 이벤트보다 먼저 CANCELED를 확인해도 추가 환불하지 않는다. 회수 원본 컬럼과 제약은 V24의 출고 테이블 생성 정의에 포함한다. 별도 V25 증분은 없으며 빈 개발 DB에서 처음부터 현재 구조를 생성한다.

취소·피킹·포장·실제 출고·반품은 reservation → SKU → 로트 잔량 순으로 잠근다. 응답 유실 시 같은 ID 조회/재전송으로 보존된 상태를 얻는다. 원격 HTTP는 기존처럼 주문 DB 트랜잭션 밖에서 실행한다.

## 배송과 반품

배송은 OUTBOUND_SHIPPED 이후에만 direct_shipment를 생성한다. 출고 지시 ID PK로 동일 품목 중복 생성을 막고 원본 내용을 대조한다. 직매입은 별도 테이블이므로 기존 입점 shipping의 seller_order_id UNIQUE·취소 승인·자동 준비/배송 동작을 유지한다.

직매입도 현재 프로젝트의 로컬 배송 시뮬레이션을 사용한다. 기본 10초 후 영속 STARTED 지시를 스케줄러가 COMPLETED로 전환하고 DIRECT_SHIPPING_COMPLETED를 Outbox에 남긴다. 재고는 이를 소비해 지시 deliveredAt을 기록한다. 실제 택배사 호출·송장 발급·택배 추적 성공을 의미하지 않는다.

반품은 출고 품목 전량을 한 번만 접수한다. 동일 returnId의 다른 원본과 동일 출고의 두 번째 returnId는 409다. 접수/수령만으로 수량·환불을 늘리지 않는다. 수령 후 원 출고 로트마다 good+damaged=출고수량인 검수만 받는다.

정상은 원 로트·원 창고로 복원하고 불량은 같은 공급사/로트 코드/기한의 DAMAGED 로트로 onHand와 blocked를 함께 더한다. 만료된 정상 반품은 기존 사용기한 판정으로 판매 가능량에서 제외된다. 원 출고/확보 기록은 보존한다. 검수·수량·작업자 원장·반품 상태·DIRECT_RETURN_INSPECTED는 원자적이다. 주문은 검수 이벤트의 품목 원본을 대조해 기존 S5 품목 환불을 한 번 발행한다. 직매입 정산은 생성/차감하지 않는다.

## 범위와 완료 판정

S6 자체 범위에는 S8 실사/운영 화면·품목 일부 수량 반품·택배 수거사/교환·실제 PG/택배·운영 DB 적용은 포함하지 않는다. S6 구현/검증과 독립 리뷰 완료를 구분한다. 현재 단계의 판정과 실제 실행 근거는 .workflow/TASK.md 및 S6 결과 폴더를 따른다.

## S7 실행 시나리오 추가

현재 test-11-mixed-fulfillment.py는 S6 기본 구매/반품 이후 [S7 이동](warehouse-transfers-s7.md)을 추가 검증한다. 과거 S6의 39개 성공 단언은 당시 소스의 근거이며 현재 S7 포함 스크립트 실행 근거와 구분한다.
