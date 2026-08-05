#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/test-helpers.sh
source "$SCRIPT_DIR/lib/test-helpers.sh"

init_test "runtime-safety-config"

cleanup_test_data() {
  CLEANUP_DONE=1
}

require_cmd jq
require_cmd rg

assert_file_contains() {
  local file="$1" pattern="$2" description="$3"
  rg -q "$pattern" "$file" || fail_test "$description: $file"
}

echo "============================================================"
echo "운영 프로필/Kafka/복구 구성 정적 검증"
echo "결과 경로: $OUT_DIR"
echo "============================================================"

cd "$ROOT_DIR"

echo "[단계] 모든 DB 서비스의 dev/docker/prod JPA·Flyway 정책 검증"
SERVICES=(order payment product shipping notification settlement user)
for service in "${SERVICES[@]}"; do
  resource_dir="$service-service/src/main/resources"
  build_file="$service-service/build.gradle.kts"
  for required_file in application.yml application-dev.yml application-docker.yml application-prod.yml; do
    [[ -f "$resource_dir/$required_file" ]] || fail_test "$service-service에 $required_file 파일이 없습니다"
  done

  assert_file_contains "$resource_dir/application.yml" 'ddl-auto:[[:space:]]*validate' "$service 기본 프로필이 validate가 아닙니다"
  assert_file_contains "$resource_dir/application.yml" 'flyway:' "$service 기본 Flyway 설정이 없습니다"
  assert_file_contains "$resource_dir/application-dev.yml" 'ddl-auto:[[:space:]]*update' "$service dev 프로필이 update가 아닙니다"
  assert_file_contains "$resource_dir/application-dev.yml" 'enabled:[[:space:]]*false' "$service dev Flyway가 비활성화되지 않았습니다"
  assert_file_contains "$resource_dir/application-docker.yml" 'ddl-auto:[[:space:]]*update' "$service docker 프로필이 update가 아닙니다"
  assert_file_contains "$resource_dir/application-docker.yml" 'enabled:[[:space:]]*false' "$service docker Flyway가 비활성화되지 않았습니다"
  assert_file_contains "$resource_dir/application-prod.yml" 'ddl-auto:[[:space:]]*validate' "$service prod 프로필이 validate가 아닙니다"
  assert_file_contains "$resource_dir/application-prod.yml" 'enabled:[[:space:]]*true' "$service prod Flyway가 활성화되지 않았습니다"
  assert_file_contains "$build_file" 'org\.flywaydb:flyway-core' "$service Flyway core 의존성이 없습니다"
  assert_file_contains "$build_file" 'org\.flywaydb:flyway-database-postgresql' "$service PostgreSQL Flyway 의존성이 없습니다"
done
assert_file_contains user-service/src/main/resources/application-docker.yml 'jdbc:postgresql://postgres:5432/user_service' "user-service Docker DB 주소가 컨테이너 호스트를 사용하지 않습니다"
assert_file_contains user-service/src/main/resources/application-docker.yml 'redis-node-1:7001' "user-service Docker Redis 클러스터 주소가 없습니다"

echo "[단계] 테스트 시드가 dev/local에서만 활성화되는지 검증"
assert_file_contains user-service/src/main/kotlin/com/eventfulcommerce/user/config/DataInitializer.kt '@Profile\("dev", "local"\)' "user-service 시드 프로필 제한이 없습니다"
assert_file_contains product-service/src/main/kotlin/com/eventfulcommerce/product/config/DataInitializer.kt '@Profile\("dev", "local"\)' "product-service 시드 프로필 제한이 없습니다"

echo "[단계] Outbox 상태/마이그레이션과 DLT 무한 아카이브 재시도 검증"
assert_file_contains common-outbox/src/main/kotlin/com/eventfulcommerce/common/OutboxStatus.kt 'PROCESSING' "Outbox PROCESSING 상태가 없습니다"
assert_file_contains common-outbox/src/main/resources/db/migration/V13__outbox_processing_status.sql "'PROCESSING'" "기존 DB가 PROCESSING을 허용하는 마이그레이션이 없습니다"
assert_file_contains common-idempotency/src/main/resources/db/migration/V21__dead_letter_event.sql 'uk_dead_letter_event_origin' "DLT 원본 위치 유니크 제약이 없습니다"
assert_file_contains common-idempotency/src/main/kotlin/com/eventfulcommerce/common/DeadLetterKafkaConfig.kt 'UNLIMITED_ATTEMPTS' "DLT 아카이브 무한 재시도가 설정되지 않았습니다"

echo "[단계] Kafka 내부/외부 리스너와 데이터 볼륨 검증"
assert_file_contains docker-compose.yml 'INTERNAL://0\.0\.0\.0:9092,EXTERNAL://0\.0\.0\.0:29092' "Kafka 이중 리스너가 없습니다"
assert_file_contains docker-compose.yml 'INTERNAL://kafka:9092,EXTERNAL://localhost:9092' "Kafka advertised listener가 올바르지 않습니다"
assert_file_contains docker-compose.yml 'kafka-data:/var/lib/kafka/data' "Kafka 데이터 볼륨 마운트가 없습니다"
assert_file_contains docker-compose.yml '^[[:space:]]+kafka-data:' "Kafka named volume 선언이 없습니다"

echo "[단계] 배송 after-commit worker/복구 스케줄러와 운영 경보 검증"
[[ -f shipping-service/src/main/kotlin/com/eventfulcommerce/shipping/service/ShippingCompletionWorker.kt ]] \
  || fail_test "ShippingCompletionWorker가 없습니다"
[[ -f shipping-service/src/main/kotlin/com/eventfulcommerce/shipping/service/ShippingCompletionRecoveryScheduler.kt ]] \
  || fail_test "ShippingCompletionRecoveryScheduler가 없습니다"
assert_file_contains shipping-service/src/main/kotlin/com/eventfulcommerce/shipping/service/ShippingCompletionWorker.kt '@Async' "배송 완료 worker가 비동기로 실행되지 않습니다"
assert_file_contains monitoring/prometheus/alerts.yml 'eventful_dlt_events\{status="PENDING"\}[[:space:]]*>[[:space:]]*0' "DLT PENDING 경보가 없습니다"

BASELINE_COUNT=$(rg --files | awk '/\/db\/migration\/V0__baseline\.sql$/ {count++} END {print count+0}')
RESULT_DATA_JSON=$(jq -n \
  --argjson checkedServices "${#SERVICES[@]}" \
  --argjson baselineCount "$BASELINE_COUNT" \
  '{checkedServices:$checkedServices,baselineCount:$baselineCount,kafkaListeners:"INTERNAL+EXTERNAL",seedProfiles:["dev","local"]}')
pass_test "7개 DB 서비스의 프로필 안전성, Kafka 영속성, Outbox/DLT/배송 복구 구성을 검증했습니다. V0 baseline 수=$BASELINE_COUNT"
