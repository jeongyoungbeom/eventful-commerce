#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/test-helpers.sh
source "$SCRIPT_DIR/lib/test-helpers.sh"

init_test "dlt-archive-observability"

cleanup_test_data() {
  [[ "$CLEANUP_DONE" == "1" ]] && return 0
  CLEANUP_DONE=1
  [[ "${KEEP_TEST_DATA:-0}" == "1" ]] && return 0
  if [[ -n "${MARKER:-}" ]]; then
    echo "[정리] 테스트 DLT 아카이브 행을 삭제합니다"
    for db in order_service notification_service settlement_service; do
      psql_exec "$db" "delete from dead_letter_event where payload like '%$MARKER%';"
    done
  fi
}

require_cmd jq
require_cmd docker
require_cmd awk

MARKER="dlt-test-$(date +%s)-$(uuid_value)"
DLT_PAYLOAD=$(jq -cn --arg marker "$MARKER" '{marker:$marker,reason:"intentional DLT archive test"}')

echo "============================================================"
echo "DLT DB 아카이브 + Prometheus 관측성 테스트"
echo "marker=$MARKER"
echo "결과 경로: $OUT_DIR"
echo "============================================================"

echo "[단계] payment-events.DLT에 테스트 레코드 직접 발행"
printf '%s\n' "$DLT_PAYLOAD" | docker exec -i eventful-kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic payment-events.DLT >/dev/null

echo "[단계] 주문 서비스 DLT 아카이브 테이블 저장 대기"
for _ in $(seq 1 40); do
  DLT_ROW=$(psql_value order_service "select id || '|' || original_topic || '|' || original_partition || '|' || original_offset || '|' || status || '|' || replay_count from dead_letter_event where payload like '%$MARKER%' order by received_at desc limit 1;" 2>/dev/null || true)
  [[ -n "$DLT_ROW" ]] && break
  sleep 1
done
[[ -n "${DLT_ROW:-}" ]] || fail_test "DLT 레코드가 order_service.dead_letter_event에 저장되지 않았습니다"

IFS='|' read -r DLT_EVENT_ID ORIGINAL_TOPIC ORIGINAL_PARTITION ORIGINAL_OFFSET DLT_STATUS REPLAY_COUNT <<< "$DLT_ROW"
[[ "$ORIGINAL_TOPIC" == "payment-events" ]] || fail_test "원본 topic 복원이 올바르지 않습니다: $ORIGINAL_TOPIC"
[[ "$DLT_STATUS" == "PENDING" && "$REPLAY_COUNT" == "0" ]] \
  || fail_test "초기 DLT 상태가 올바르지 않습니다: status=$DLT_STATUS replayCount=$REPLAY_COUNT"
[[ "$ORIGINAL_PARTITION" =~ ^[0-9]+$ && "$ORIGINAL_OFFSET" =~ ^[0-9]+$ ]] \
  || fail_test "DLT partition/offset이 저장되지 않았습니다: partition=$ORIGINAL_PARTITION offset=$ORIGINAL_OFFSET"

echo "[단계] 원본 위치 유니크 제약과 PENDING Prometheus 지표 검증"
UNIQUE_CONSTRAINT=$(psql_value order_service "select count(*) from pg_constraint where conrelid='dead_letter_event'::regclass and conname='uk_dead_letter_event_origin' and contype='u';")
[[ "$UNIQUE_CONSTRAINT" == "1" ]] || fail_test "DLT 원본 위치 유니크 제약이 없습니다"

METRICS=$(docker exec order-service wget -qO- http://localhost:8081/actuator/prometheus)
PENDING_METRIC=$(awk '/^eventful_dlt_events\{.*status="PENDING"/ {print $2; exit}' <<< "$METRICS")
[[ -n "$PENDING_METRIC" ]] || fail_test "eventful_dlt_events PENDING 지표를 찾지 못했습니다"
awk -v value="$PENDING_METRIC" 'BEGIN { exit !(value >= 1) }' \
  || fail_test "PENDING DLT 지표가 1보다 작습니다: $PENDING_METRIC"

DLT_ARCHIVE_COUNT=$(psql_value order_service "select count(*) from dead_letter_event where original_topic='payment-events' and original_partition=$ORIGINAL_PARTITION and original_offset=$ORIGINAL_OFFSET;")
[[ "$DLT_ARCHIVE_COUNT" == "1" ]] || fail_test "동일 원본 위치 DLT 행이 중복 저장됐습니다: $DLT_ARCHIVE_COUNT"

RESULT_DATA_JSON=$(jq -n \
  --arg marker "$MARKER" \
  --arg eventId "$DLT_EVENT_ID" \
  --arg originalTopic "$ORIGINAL_TOPIC" \
  --arg status "$DLT_STATUS" \
  --arg pendingMetric "$PENDING_METRIC" \
  --argjson originalPartition "$ORIGINAL_PARTITION" \
  --argjson originalOffset "$ORIGINAL_OFFSET" \
  '{marker:$marker,eventId:$eventId,originalTopic:$originalTopic,originalPartition:$originalPartition,originalOffset:$originalOffset,status:$status,pendingMetric:$pendingMetric}')
pass_test "DLT 레코드가 원본 위치와 함께 PENDING으로 한 번 저장되고 Prometheus 지표에 노출됨을 검증했습니다."
