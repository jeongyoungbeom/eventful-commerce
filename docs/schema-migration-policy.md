# 스키마 마이그레이션 운영 정책

## 프로필별 동작

- `dev`(기본): Hibernate `update`, Flyway 비활성화. 로컬 개발과 테스트 데이터 시드에만 사용합니다.
- `docker`: Hibernate `update`, Flyway 비활성화. 데모·개발용 Compose 환경에만 사용합니다.
- `prod`: Hibernate `validate`, Flyway 활성화, `clean` 금지. 애플리케이션은 스키마를 변경하지 않습니다.

`prod`에서 테이블 또는 컬럼이 누락되면 시작 단계에서 실패해야 합니다. 자동 생성으로 조용히 배포되는 것보다, 배포를 중단하고 마이그레이션을 적용하는 편이 안전합니다.

## 신규 inventory-service 예외

신규 inventory-service는 기본/dev/docker/prod 모두 Hibernate validate + Flyway 활성화다. 빈 전용 DB에서 V1 전체 기준선과 공통 증분을 적용하며 수량 CHECK/FK/UNIQUE를 함께 검증한다. dev/docker에서 스키마 자동 update로 재고 제약이 빠지는 것을 허용하지 않는다. 기존 DB·데이터를 초기화하지 않으며 inventory_service 전용 DB 추가는 기존 볼륨 초기화와 구분한다. 자세한 실행 방법은 [S2 가이드](inventory-service-s2.md)를 따른다.

## 기존 데이터베이스 전환

현재 로컬 개발은 보존할 기존 데이터가 없는 초기 개발 전제다. 아직 배포하지 않은 기능의 스키마 수정은 해당 테이블의 생성 정의에 반영하며, 기존 개발 DB를 이전하기 위한 증분·backfill·호환 분기를 추가하지 않는다. S6 회수 원본 컬럼과 CHECK는 V24의 CREATE TABLE에 포함하고 별도 S6 회수 증분 V25는 사용하지 않는다. S7의 V25__warehouse_transfers.sql은 신규 이동 테이블의 최초 생성이며, 이동 확보 컬럼과 잔량 제약은 V1 생성 정의에 포함한다. S8의 V26__stock_counts_and_reconciliation.sql은 실사·대사 테이블의 최초 생성이며 STOCK_COUNT 원장 유형은 기존 V24의 최종 제약 정의에 포함한다. 빈 전용 개발/테스트 DB에서 생성·제약을 검증한다. 기존 개발 DB나 볼륨 삭제는 별도 작업이며 이 정책만으로 자동 수행하지 않는다.

아래 전환 절차는 이미 운영 중인 DB를 실제로 도입할 때 적용한다.

1. 서비스 DB를 백업하고 현재 DDL을 스냅샷으로 보관합니다.
2. 그 DDL을 서비스별 `V0__baseline.sql`로 검토·커밋합니다. 이 프로젝트의 기존 `V1` 이후 파일은 그 기준선 이후의 증분 변경입니다.
3. 이미 운영 중인 DB에는 승인된 Flyway baseline 절차로 스키마 이력을 등록합니다. `baseline-on-migrate`를 애플리케이션 설정에서 켜지 않습니다.
4. 빈 운영 DB에서는 `V0`부터 Flyway를 실행한 뒤 애플리케이션을 `prod` 프로필로 시작합니다.

## 배포 규칙

- 엔티티 변경과 마이그레이션 SQL은 같은 변경 묶음으로 리뷰합니다.
- `prod`에서 `ddl-auto=update/create` 또는 Flyway `clean`은 사용하지 않습니다.
- 마이그레이션 적용 전후로 `flyway_schema_history`와 애플리케이션의 Hibernate 검증 결과를 확인합니다.


## S4 혼합 주문 시도

order-service의 V7__mixed_order_attempt.sql은 영속 의도·품목 원본·확인 버전과 복구 기한·다음 품목 위치(recovery_cursor, 기본0·비음수)를 추가한다. 아직 배포 전인 초기 생성 정의에 반영하며 기존 DB에 이 변경을 자동 적용하지 않는다. orders FK·비음수 비용·구성/확인 버전·JSON 배열 CHECK를 포함한다. 기존 dev/docker update·prod validate/Flyway 정책을 유지하며 로컬 Hibernate update만으로 FK/CHECK 적용을 보증하지 않는다. S4 테스트는 임시 PostgreSQL에서 V7 제약을 직접 검증한다. 서비스/공유 DB에는 실행하지 않았다. 상세 계약은 [S4 혼합 주문](mixed-orders-s4.md)을 따른다.


## S5 혼합 결제·품목 환불

order V8은 mixed_order 표식과 결제 상태를, payment V5는 refund_key·판매 유형·품목 원본과 UNIQUE 교체를, inventory V23은 할당 취소 상태·원장 CHECK를 추가한다. payment V5는 기존 (payment_id,seller_order_id) UNIQUE를 제거해야 하므로 기존 개발 DB에서 Hibernate update만 실행하면 여러 품목 환불이 막힌다. 기존 프로필 정책은 유지하고 승인된 대상에서 SQL을 적용한다. inventory는 기존처럼 Flyway가 증분을 적용한다. S5 검증은 격리 테스트 DB에만 적용하며 실제 서비스/공유 DB를 변경하지 않았다. 상세는 [S5 계약](mixed-payments-s5.md)을 따른다.
