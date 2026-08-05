#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/test-helpers.sh
source "$SCRIPT_DIR/lib/test-helpers.sh"

init_test "orphan-hold-cleanup"

cleanup_test_data() {
  [[ "$CLEANUP_DONE" == "1" ]] && return 0
  CLEANUP_DONE=1
  [[ "${KEEP_TEST_DATA:-0}" == "1" ]] && return 0
  echo "[정리] 고아 hold 테스트 데이터를 삭제합니다"
  if [[ -n "${PRODUCT_ID:-}" && -n "${RESERVATION_ID:-}" ]]; then
    redis_del "{product:$PRODUCT_ID}:hold:$RESERVATION_ID"
  fi
  cleanup_common_order_data
}

require_cmd curl
require_cmd jq
require_cmd docker

TS=$(date +%s)
INITIAL_STOCK="${INITIAL_STOCK:-12}"
ORPHAN_QUANTITY="${ORPHAN_QUANTITY:-4}"

echo "============================================================"
echo "Redis 고아 hold 자동 해제 테스트"
echo "초기 재고=$INITIAL_STOCK, 고아 예약 수량=$ORPHAN_QUANTITY"
echo "결과 경로: $OUT_DIR"
echo "============================================================"

echo "[단계] 상품과 Redis 재고 준비"
create_test_principals "orphan" "$TS"
create_test_product "$TS" "$INITIAL_STOCK"
STOCK_KEY="{product:$PRODUCT_ID}:stock"
HOLD_COUNT_KEY="{product:$PRODUCT_ID}:holdCount"
EXPIRATION_KEY="{product:$PRODUCT_ID}:holdExpirations"
wait_for_db_value order_service "select count(*) from product_read_model where product_id='$PRODUCT_ID';" "1" 60 \
  || fail_test "상품 읽기 모델이 생성되지 않았습니다"
wait_for_redis_value "$STOCK_KEY" "$INITIAL_STOCK" 60 \
  || fail_test "Redis 초기 재고가 준비되지 않았습니다"

echo "[단계] DB 주문 없이 만료된 Redis hold를 생성해 서버 종료 직후 상태를 모사"
ORPHAN_ORDER_ID=$(uuid_value)
RESERVATION_ID=$(uuid_value)
HOLD_KEY="{product:$PRODUCT_ID}:hold:$RESERVATION_ID"
EXPIRED_AT_MILLIS=$(( $(date +%s) * 1000 - 5000 ))

redis_cli SADD inventory:reservation-products "$PRODUCT_ID" >/dev/null
redis_cli DECRBY "$STOCK_KEY" "$ORPHAN_QUANTITY" >/dev/null
redis_cli INCRBY "$HOLD_COUNT_KEY" "$ORPHAN_QUANTITY" >/dev/null
redis_cli HSET "$HOLD_KEY" \
  orderId "$ORPHAN_ORDER_ID" \
  productId "$PRODUCT_ID" \
  reservationId "$RESERVATION_ID" \
  quantity "$ORPHAN_QUANTITY" \
  expiresAtEpochMilli "$EXPIRED_AT_MILLIS" \
  status RESERVED >/dev/null
redis_cli ZADD "$EXPIRATION_KEY" "$EXPIRED_AT_MILLIS" "$RESERVATION_ID" >/dev/null

EXPECTED_HELD_STOCK=$((INITIAL_STOCK - ORPHAN_QUANTITY))
[[ "$(redis_cli GET "$STOCK_KEY")" == "$EXPECTED_HELD_STOCK" ]] || fail_test "고아 hold 생성 후 재고 차감값이 올바르지 않습니다"
[[ "$(redis_cli GET "$HOLD_COUNT_KEY")" == "$ORPHAN_QUANTITY" ]] || fail_test "고아 holdCount가 올바르지 않습니다"
[[ "$(psql_value order_service "select count(*) from orders where id='$ORPHAN_ORDER_ID';")" == "0" ]] || fail_test "고아 주문 ID가 DB에 존재합니다"

echo "[단계] 10초 주기 정리 스케줄러가 고아 hold를 해제할 때까지 대기"
for _ in $(seq 1 35); do
  HOLD_STATUS=$(redis_cli HGET "$HOLD_KEY" status || true)
  CURRENT_STOCK=$(redis_cli GET "$STOCK_KEY" || true)
  CURRENT_HOLD_COUNT=$(redis_cli GET "$HOLD_COUNT_KEY" || true)
  if [[ "$HOLD_STATUS" == "RELEASED" && "$CURRENT_STOCK" == "$INITIAL_STOCK" && "$CURRENT_HOLD_COUNT" == "0" ]]; then
    break
  fi
  sleep 1
done

[[ "${HOLD_STATUS:-}" == "RELEASED" ]] || fail_test "고아 hold가 RELEASED 상태로 전환되지 않았습니다: ${HOLD_STATUS:-missing}"
[[ "${CURRENT_STOCK:-}" == "$INITIAL_STOCK" ]] || fail_test "고아 hold 해제 후 Redis 재고가 복구되지 않았습니다: ${CURRENT_STOCK:-missing}"
[[ "${CURRENT_HOLD_COUNT:-}" == "0" ]] || fail_test "고아 hold 해제 후 holdCount가 0이 아닙니다: ${CURRENT_HOLD_COUNT:-missing}"
[[ -z "$(redis_cli ZSCORE "$EXPIRATION_KEY" "$RESERVATION_ID" || true)" ]] || fail_test "만료 인덱스에서 고아 예약이 제거되지 않았습니다"
HOLD_TTL=$(redis_cli PTTL "$HOLD_KEY")
(( HOLD_TTL > 0 )) || fail_test "RELEASED tombstone에 보존 TTL이 설정되지 않았습니다: ttl=$HOLD_TTL"

echo "[단계] 다음 스케줄 주기에도 재고가 중복 복구되지 않는지 검증"
sleep "${SECOND_PASS_WAIT_SECONDS:-11}"
[[ "$(redis_cli GET "$STOCK_KEY")" == "$INITIAL_STOCK" ]] || fail_test "고아 hold가 두 번 해제되어 재고가 과다 복구됐습니다"
[[ "$(redis_cli HGET "$HOLD_KEY" status)" == "RELEASED" ]] || fail_test "고아 hold tombstone 상태가 바뀌었습니다"

RESULT_DATA_JSON=$(jq -n \
  --arg productId "$PRODUCT_ID" \
  --arg orphanOrderId "$ORPHAN_ORDER_ID" \
  --arg reservationId "$RESERVATION_ID" \
  --arg finalState "$HOLD_STATUS" \
  --argjson initialStock "$INITIAL_STOCK" \
  --argjson quantity "$ORPHAN_QUANTITY" \
  --argjson tombstoneTtlMillis "$HOLD_TTL" \
  '{productId:$productId,orphanOrderId:$orphanOrderId,reservationId:$reservationId,finalState:$finalState,initialStock:$initialStock,quantity:$quantity,tombstoneTtlMillis:$tombstoneTtlMillis}')
pass_test "DB 주문이 없는 만료 hold를 스케줄러가 한 번만 해제하고 Redis 재고를 복구함을 검증했습니다."
