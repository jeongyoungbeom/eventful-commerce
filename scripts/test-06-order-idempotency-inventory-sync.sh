#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/test-helpers.sh
source "$SCRIPT_DIR/lib/test-helpers.sh"

init_test "order-idempotency-inventory-sync"

cleanup_test_data() {
  [[ "$CLEANUP_DONE" == "1" ]] && return 0
  CLEANUP_DONE=1
  [[ "${KEEP_TEST_DATA:-0}" == "1" ]] && return 0
  echo "[정리] 멱등성/재고 동기화 테스트 데이터를 삭제합니다"
  if [[ -n "${LEDGER_EVENT_ID:-}" ]]; then
    psql_exec product_service "delete from processed_event where event_id in ('$LEDGER_EVENT_ID','${OLD_SNAPSHOT_EVENT_ID:-00000000-0000-0000-0000-000000000000}','${NEW_SNAPSHOT_EVENT_ID:-00000000-0000-0000-0000-000000000000}','${STALE_LEDGER_EVENT_ID:-00000000-0000-0000-0000-000000000000}');"
  fi
  cleanup_common_order_data
}

publish_inventory_event() {
  local event_id="$1" event_type="$2" occurred_at="$3" payload="$4"
  local message
  message=$(jq -cn \
    --arg eventId "$event_id" \
    --arg aggregateId "$PRODUCT_ID" \
    --arg eventType "$event_type" \
    --arg occurredAt "$occurred_at" \
    --arg payload "$payload" \
    '{eventId:$eventId,aggregateType:"INVENTORY",aggregateId:$aggregateId,eventType:$eventType,occurredAt:$occurredAt,payload:$payload}')
  printf '%s\n' "$message" | docker exec -i eventful-kafka \
    /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic order-events >/dev/null
}

require_cmd curl
require_cmd jq
require_cmd docker

TS=$(date +%s)
INITIAL_STOCK="${INITIAL_STOCK:-20}"
ORDER_QUANTITY="${ORDER_QUANTITY:-3}"
EXPECTED_RESERVED_STOCK=$((INITIAL_STOCK - ORDER_QUANTITY))

echo "============================================================"
echo "주문 요청 멱등성 + Redis/Product DB 재고 동기화 테스트"
echo "초기 재고=$INITIAL_STOCK, 주문 수량=$ORDER_QUANTITY"
echo "결과 경로: $OUT_DIR"
echo "============================================================"

echo "[단계] 판매자/사용자/상품 생성"
create_test_principals "idem_stock" "$TS"
create_test_product "$TS" "$INITIAL_STOCK"
echo "[정보] productId=$PRODUCT_ID"

STOCK_KEY="{product:$PRODUCT_ID}:stock"
HOLD_COUNT_KEY="{product:$PRODUCT_ID}:holdCount"
wait_for_db_value order_service "select count(*) from product_read_model where product_id='$PRODUCT_ID';" "1" 60 \
  || fail_test "상품 읽기 모델이 생성되지 않았습니다"
wait_for_redis_value "$STOCK_KEY" "$INITIAL_STOCK" 60 \
  || fail_test "Redis 초기 재고가 생성되지 않았습니다"

echo "[단계] 동일 Idempotency-Key로 같은 주문을 두 번 전송"
ORDER_KEY="test-06-$TS-$(uuid_value)"
ORDER_PAYLOAD="{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":$ORDER_QUANTITY}]}"
http_json POST "$GATEWAY_URL/api/orders" "$ORDER_PAYLOAD" "$USER_TOKEN" "$USER_ID" "USER" "$ORDER_KEY"
[[ "$RESPONSE_STATUS" == "200" ]] || fail_test "첫 주문 생성 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
FIRST_RESPONSE="$RESPONSE_BODY"
ORDER_ID=$(jq -r '.orderId' <<< "$FIRST_RESPONSE")
[[ -n "$ORDER_ID" && "$ORDER_ID" != "null" ]] || fail_test "첫 주문에 orderId가 없습니다: $FIRST_RESPONSE"

http_json POST "$GATEWAY_URL/api/orders" "$ORDER_PAYLOAD" "$USER_TOKEN" "$USER_ID" "USER" "$ORDER_KEY"
[[ "$RESPONSE_STATUS" == "200" ]] || fail_test "멱등 재시도 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
SECOND_ORDER_ID=$(jq -r '.orderId' <<< "$RESPONSE_BODY")
[[ "$SECOND_ORDER_ID" == "$ORDER_ID" ]] || fail_test "멱등 재시도가 다른 주문을 만들었습니다: first=$ORDER_ID second=$SECOND_ORDER_ID"

echo "[단계] 같은 키를 다른 요청 본문에 재사용하면 충돌하는지 검증"
CONFLICT_PAYLOAD="{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":$((ORDER_QUANTITY + 1))}]}"
http_json POST "$GATEWAY_URL/api/orders" "$CONFLICT_PAYLOAD" "$USER_TOKEN" "$USER_ID" "USER" "$ORDER_KEY"
[[ "$RESPONSE_STATUS" == "409" ]] || fail_test "다른 본문의 Idempotency-Key 재사용이 차단되지 않았습니다: HTTP $RESPONSE_STATUS $RESPONSE_BODY"

ORDER_COUNT=$(psql_value order_service "select count(*) from orders where id='$ORDER_ID';")
IDEMPOTENCY_COUNT=$(psql_value order_service "select count(*) from order_request_idempotency where user_id='$USER_ID' and idempotency_key='$ORDER_KEY' and order_id='$ORDER_ID';")
[[ "$ORDER_COUNT" == "1" && "$IDEMPOTENCY_COUNT" == "1" ]] \
  || fail_test "주문/멱등 레코드 수가 올바르지 않습니다: orders=$ORDER_COUNT idempotency=$IDEMPOTENCY_COUNT"

echo "[단계] 예약 증분 이벤트가 Product DB까지 반영되고 Redis가 이중 차감되지 않는지 검증"
wait_for_redis_value "$STOCK_KEY" "$EXPECTED_RESERVED_STOCK" 30 \
  || fail_test "Redis 예약 재고가 기대값과 다릅니다"
wait_for_redis_value "$HOLD_COUNT_KEY" "$ORDER_QUANTITY" 30 \
  || fail_test "Redis hold 수량이 기대값과 다릅니다"
wait_for_db_value product_service "select stock from products where id='$PRODUCT_ID';" "$EXPECTED_RESERVED_STOCK" 60 \
  || fail_test "INVENTORY_STOCK_ADJUSTED가 Product DB에 반영되지 않았습니다"
wait_for_db_value order_service "select stock from product_read_model where product_id='$PRODUCT_ID';" "$EXPECTED_RESERVED_STOCK" 60 \
  || fail_test "PRODUCT_STOCK_UPDATED가 주문 읽기 모델에 반영되지 않았습니다"

RESERVE_EVENT_COUNT=$(psql_value order_service "select count(*) from outbox_event where event_type='INVENTORY_STOCK_ADJUSTED' and aggregate_id='$PRODUCT_ID' and payload like '%$ORDER_ID%' and payload like '%\"stockDelta\":-$ORDER_QUANTITY%';")
REDIS_ALREADY_ADJUSTED_COUNT=$(psql_value product_service "select count(*) from outbox_event where event_type='PRODUCT_STOCK_UPDATED' and aggregate_id='$PRODUCT_ID' and payload like '%\"redisAlreadyAdjusted\":true%';")
[[ "$RESERVE_EVENT_COUNT" == "1" ]] || fail_test "예약 재고 원장 이벤트가 1건이 아닙니다: $RESERVE_EVENT_COUNT"
[[ "$REDIS_ALREADY_ADJUSTED_COUNT" == "1" ]] || fail_test "redisAlreadyAdjusted=true 상품 이벤트가 1건이 아닙니다: $REDIS_ALREADY_ADJUSTED_COUNT"
sleep 2
[[ "$(redis_cli GET "$STOCK_KEY")" == "$EXPECTED_RESERVED_STOCK" ]] \
  || fail_test "상품 이벤트 소비 후 Redis 재고가 이중 차감되었습니다"

echo "[단계] 결제 전 취소 시 Redis와 Product DB가 각각 한 번만 복구되는지 검증"
http_json POST "$GATEWAY_URL/api/orders/$ORDER_ID/cancel" "" "$USER_TOKEN" "$USER_ID" "USER"
[[ "$RESPONSE_STATUS" == "200" ]] || fail_test "주문 취소 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
wait_for_order_status "$ORDER_ID" "ORDER_CANCELED" 30 || fail_test "주문이 ORDER_CANCELED가 되지 않았습니다"
wait_for_redis_value "$STOCK_KEY" "$INITIAL_STOCK" 30 || fail_test "취소 후 Redis 재고가 복구되지 않았습니다"
wait_for_redis_value "$HOLD_COUNT_KEY" "0" 30 || fail_test "취소 후 Redis hold 수량이 0이 아닙니다"
wait_for_db_value product_service "select stock from products where id='$PRODUCT_ID';" "$INITIAL_STOCK" 60 \
  || fail_test "취소 재고 이벤트가 Product DB에 반영되지 않았습니다"

http_json POST "$GATEWAY_URL/api/orders/$ORDER_ID/cancel" "" "$USER_TOKEN" "$USER_ID" "USER"
[[ "$RESPONSE_STATUS" == "400" ]] || fail_test "중복 취소가 성공으로 처리되었습니다: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
RESTORE_EVENT_COUNT=$(psql_value order_service "select count(*) from outbox_event where event_type='INVENTORY_STOCK_ADJUSTED' and aggregate_id='$PRODUCT_ID' and payload like '%$ORDER_ID%' and payload like '%\"stockDelta\":$ORDER_QUANTITY%';")
[[ "$RESTORE_EVENT_COUNT" == "1" ]] || fail_test "재고 복구 이벤트가 1건이 아닙니다: $RESTORE_EVENT_COUNT"
[[ "$(redis_cli GET "$STOCK_KEY")" == "$INITIAL_STOCK" ]] || fail_test "중복 취소 후 Redis 재고가 다시 증가했습니다"

echo "[단계] 증분/스냅샷 이벤트 순서가 뒤바뀌어도 최신 Product DB 재고가 보존되는지 검증"
LEDGER_EVENT_ID=$(uuid_value)
OLD_SNAPSHOT_EVENT_ID=$(uuid_value)
NEW_SNAPSHOT_EVENT_ID=$(uuid_value)
STALE_LEDGER_EVENT_ID=$(uuid_value)
SYNTHETIC_RESERVATION_ID=$(uuid_value)
OLD_SNAPSHOT_AT=$(date -u -d '10 minutes ago' +"%Y-%m-%dT%H:%M:%S.%NZ")
LEDGER_AT=$(date -u -d '5 minutes ago' +"%Y-%m-%dT%H:%M:%S.%NZ")
STALE_LEDGER_AT=$(date -u -d '2 minutes ago' +"%Y-%m-%dT%H:%M:%S.%NZ")
NEW_SNAPSHOT_AT=$(date -u +"%Y-%m-%dT%H:%M:%S.%NZ")

redis_cli SET "$STOCK_KEY" "$((INITIAL_STOCK - 3))" >/dev/null
LEDGER_PAYLOAD=$(jq -cn \
  --arg orderId "$ORDER_ID" --arg productId "$PRODUCT_ID" --arg reservationId "$SYNTHETIC_RESERVATION_ID" \
  '{orderId:$orderId,productId:$productId,reservationId:$reservationId,stockDelta:-3,reason:"TEST_OUT_OF_ORDER_LEDGER"}')
publish_inventory_event "$LEDGER_EVENT_ID" "INVENTORY_STOCK_ADJUSTED" "$LEDGER_AT" "$LEDGER_PAYLOAD"
wait_for_db_value product_service "select stock from products where id='$PRODUCT_ID';" "$((INITIAL_STOCK - 3))" 60 \
  || fail_test "기준 증분 이벤트가 Product DB에 반영되지 않았습니다"

OLD_SNAPSHOT_PAYLOAD=$(jq -cn --arg productId "$PRODUCT_ID" --argjson stock "$INITIAL_STOCK" \
  '{productId:$productId,availableStock:$stock}')
publish_inventory_event "$OLD_SNAPSHOT_EVENT_ID" "INVENTORY_STOCK_SNAPSHOT" "$OLD_SNAPSHOT_AT" "$OLD_SNAPSHOT_PAYLOAD"
wait_for_db_value product_service "select count(*) from processed_event where event_id='$OLD_SNAPSHOT_EVENT_ID';" "1" 60 \
  || fail_test "오래된 스냅샷 이벤트가 소비되지 않았습니다"
[[ "$(psql_value product_service "select stock from products where id='$PRODUCT_ID';")" == "$((INITIAL_STOCK - 3))" ]] \
  || fail_test "오래된 스냅샷이 최신 증분 재고를 덮어썼습니다"

redis_cli SET "$STOCK_KEY" "$((INITIAL_STOCK - 5))" >/dev/null
NEW_SNAPSHOT_PAYLOAD=$(jq -cn --arg productId "$PRODUCT_ID" --argjson stock "$((INITIAL_STOCK - 5))" \
  '{productId:$productId,availableStock:$stock}')
publish_inventory_event "$NEW_SNAPSHOT_EVENT_ID" "INVENTORY_STOCK_SNAPSHOT" "$NEW_SNAPSHOT_AT" "$NEW_SNAPSHOT_PAYLOAD"
wait_for_db_value product_service "select stock from products where id='$PRODUCT_ID';" "$((INITIAL_STOCK - 5))" 60 \
  || fail_test "최신 스냅샷이 Product DB에 반영되지 않았습니다"

STALE_LEDGER_PAYLOAD=$(jq -cn \
  --arg orderId "$ORDER_ID" --arg productId "$PRODUCT_ID" --arg reservationId "$(uuid_value)" \
  '{orderId:$orderId,productId:$productId,reservationId:$reservationId,stockDelta:2,reason:"TEST_STALE_LEDGER"}')
publish_inventory_event "$STALE_LEDGER_EVENT_ID" "INVENTORY_STOCK_ADJUSTED" "$STALE_LEDGER_AT" "$STALE_LEDGER_PAYLOAD"
wait_for_db_value product_service "select count(*) from processed_event where event_id='$STALE_LEDGER_EVENT_ID';" "1" 60 \
  || fail_test "오래된 증분 이벤트가 소비되지 않았습니다"
[[ "$(psql_value product_service "select stock from products where id='$PRODUCT_ID';")" == "$((INITIAL_STOCK - 5))" ]] \
  || fail_test "오래된 증분 이벤트가 최신 스냅샷 재고를 덮어썼습니다"

RESULT_DATA_JSON=$(jq -n \
  --arg productId "$PRODUCT_ID" \
  --arg orderId "$ORDER_ID" \
  --arg idempotencyKey "$ORDER_KEY" \
  --argjson initialStock "$INITIAL_STOCK" \
  --argjson quantity "$ORDER_QUANTITY" \
  --argjson reserveEventCount "$RESERVE_EVENT_COUNT" \
  --argjson restoreEventCount "$RESTORE_EVENT_COUNT" \
  --argjson reconciledStock "$((INITIAL_STOCK - 5))" \
  '{productId:$productId,orderId:$orderId,idempotencyKey:$idempotencyKey,initialStock:$initialStock,quantity:$quantity,reserveEventCount:$reserveEventCount,restoreEventCount:$restoreEventCount,reconciledStock:$reconciledStock}')
pass_test "주문 멱등성, Redis/Product DB 증분 동기화, 취소 복구와 스냅샷/증분 역순 도착 방어를 검증했습니다."
