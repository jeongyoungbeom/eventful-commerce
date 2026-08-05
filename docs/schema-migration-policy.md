# 스키마 마이그레이션 운영 정책

## 프로필별 동작

- `dev`(기본): Hibernate `update`, Flyway 비활성화. 로컬 개발과 테스트 데이터 시드에만 사용합니다.
- `docker`: Hibernate `update`, Flyway 비활성화. 데모·개발용 Compose 환경에만 사용합니다.
- `prod`: Hibernate `validate`, Flyway 활성화, `clean` 금지. 애플리케이션은 스키마를 변경하지 않습니다.

`prod`에서 테이블 또는 컬럼이 누락되면 시작 단계에서 실패해야 합니다. 자동 생성으로 조용히 배포되는 것보다, 배포를 중단하고 마이그레이션을 적용하는 편이 안전합니다.

## 기존 데이터베이스 전환

1. 서비스 DB를 백업하고 현재 DDL을 스냅샷으로 보관합니다.
2. 그 DDL을 서비스별 `V0__baseline.sql`로 검토·커밋합니다. 이 프로젝트의 기존 `V1` 이후 파일은 그 기준선 이후의 증분 변경입니다.
3. 이미 운영 중인 DB에는 승인된 Flyway baseline 절차로 스키마 이력을 등록합니다. `baseline-on-migrate`를 애플리케이션 설정에서 켜지 않습니다.
4. 빈 운영 DB에서는 `V0`부터 Flyway를 실행한 뒤 애플리케이션을 `prod` 프로필로 시작합니다.

## 배포 규칙

- 엔티티 변경과 마이그레이션 SQL은 같은 변경 묶음으로 리뷰합니다.
- `prod`에서 `ddl-auto=update/create` 또는 Flyway `clean`은 사용하지 않습니다.
- 마이그레이션 적용 전후로 `flyway_schema_history`와 애플리케이션의 Hibernate 검증 결과를 확인합니다.

