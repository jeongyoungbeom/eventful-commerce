#!/usr/bin/env bash

# Shared helpers for the resilience scenario scripts. The caller must use
# `set -euo pipefail` before sourcing this file.

HELPER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "$HELPER_DIR/.." && pwd)"

init_test() {
  TEST_NAME="$1"
  GATEWAY_URL="${GATEWAY_URL:-http://localhost}"
  RESULT_ROOT="${RESULT_ROOT:-$SCRIPTS_DIR/results}"
  RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)-$TEST_NAME}"
  OUT_DIR="$RESULT_ROOT/$RUN_ID"
  LOG_FILE="$OUT_DIR/run.log"
  RESULT_FILE="$OUT_DIR/result.json"
  START_EPOCH=$(date +%s)
  STATUS="FAILED"
  MESSAGE="테스트가 완료되지 않았습니다"
  RESULT_DATA_JSON='{}'
  CLEANUP_DONE=0

  mkdir -p "$OUT_DIR"
  exec > >(tee -a "$LOG_FILE") 2>&1
  trap test_on_exit EXIT
  trap 'MESSAGE="테스트가 인터럽트되어 중단되었습니다"; exit 130' INT TERM
}

test_on_exit() {
  local code=$?
  trap - EXIT
  if declare -F cleanup_test_data >/dev/null 2>&1; then
    cleanup_test_data || true
  fi
  finish_test
  exit "$code"
}

finish_test() {
  local end_epoch duration status_label data_json
  end_epoch=$(date +%s)
  duration=$((end_epoch - START_EPOCH))
  [[ "$STATUS" == "PASSED" ]] && status_label="성공" || status_label="실패"
  data_json="${RESULT_DATA_JSON:-{}}"
  jq -e . >/dev/null 2>&1 <<< "$data_json" || data_json='{}'

  jq -n \
    --arg test "$TEST_NAME" \
    --arg status "$STATUS" \
    --arg message "$MESSAGE" \
    --arg gatewayUrl "$GATEWAY_URL" \
    --arg resultDir "$OUT_DIR" \
    --arg logFile "$LOG_FILE" \
    --arg startedAt "$(iso_time "$START_EPOCH")" \
    --arg endedAt "$(iso_time "$end_epoch")" \
    --argjson durationSeconds "$duration" \
    --argjson data "$data_json" \
    '{test:$test,status:$status,message:$message,gatewayUrl:$gatewayUrl,resultDir:$resultDir,logFile:$logFile,startedAt:$startedAt,endedAt:$endedAt,durationSeconds:$durationSeconds,data:$data}' \
    > "$RESULT_FILE"

  echo
  echo "[결과] $status_label - $MESSAGE"
  echo "[결과 파일] $RESULT_FILE"
  echo "[로그 파일] $LOG_FILE"
}

iso_time() {
  local epoch="$1"
  date -d "@$epoch" --iso-8601=seconds 2>/dev/null || date -r "$epoch" '+%Y-%m-%dT%H:%M:%S%z'
}

pass_test() {
  STATUS="PASSED"
  MESSAGE="$1"
}

fail_test() {
  MESSAGE="$1"
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail_test "필수 명령어가 없습니다: $1"
}

uuid_value() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    tr '[:upper:]' '[:lower:]' < /proc/sys/kernel/random/uuid
  fi
}

psql_value() {
  local db="$1" sql="$2"
  docker exec eventful-postgres psql -U postgres -d "$db" -tAc "$sql"
}

psql_exec() {
  local db="$1" sql="$2"
  docker exec eventful-postgres psql -U postgres -d "$db" -v ON_ERROR_STOP=0 -q -c "$sql" >/dev/null 2>&1 || true
}

redis_cli() {
  docker exec redis-node-1 redis-cli -c -p 7001 "$@" 2>/dev/null
}

redis_del() {
  local key
  for key in "$@"; do
    [[ -n "$key" ]] || continue
    redis_cli DEL "$key" >/dev/null || true
  done
}

redis_del_pattern() {
  local pattern="$1" key
  while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    redis_del "$key"
  done < <(redis_cli --scan --pattern "$pattern" || true)
}

http_json() {
  local method="$1" url="$2" data="${3:-}" token="${4:-}"
  local user_id="${5:-}" role="${6:-}" idempotency_key="${7:-}"
  local body_file status
  local -a args
  body_file=$(mktemp)
  args=(-sS -o "$body_file" -w "%{http_code}" -X "$method" "$url" -H "Content-Type: application/json")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$user_id" ]] && args+=(-H "X-User-Id: $user_id")
  [[ -n "$role" ]] && args+=(-H "X-User-Role: $role")
  [[ -n "$idempotency_key" ]] && args+=(-H "Idempotency-Key: $idempotency_key")
  [[ -n "$data" ]] && args+=(-d "$data")
  status=$(curl "${args[@]}" || printf "000")
  RESPONSE_STATUS="${status: -3}"
  RESPONSE_BODY=$(<"$body_file")
  rm -f "$body_file"
}

http_product_create() {
  local token="$1" payload="$2" user_id="$3" role="${4:-SELLER}"
  local body_file status
  body_file=$(mktemp)
  status=$(curl -sS -o "$body_file" -w "%{http_code}" -X POST "$GATEWAY_URL/api/products" \
    -H "Authorization: Bearer $token" \
    -H "X-User-Id: $user_id" \
    -H "X-User-Role: $role" \
    -F "request=$payload;type=application/json" || printf "000")
  RESPONSE_STATUS="${status: -3}"
  RESPONSE_BODY=$(<"$body_file")
  rm -f "$body_file"
}

create_test_principals() {
  local prefix="$1" ts="$2"
  PASSWORD="Test1234!"
  SELLER_EMAIL="seller_${prefix}_${ts}@portfolio.test"
  USER_EMAIL="user_${prefix}_${ts}@portfolio.test"

  http_json POST "$GATEWAY_URL/api/auth/signup/seller" \
    "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$PASSWORD\",\"name\":\"복구테스트판매자\",\"businessName\":\"복구테스트상점\",\"businessNumber\":\"123-45-67890\",\"bankAccount\":\"110-123-456789\",\"bankCode\":\"088\"}"
  [[ "$RESPONSE_STATUS" == "201" ]] || fail_test "판매자 회원가입 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
  http_json POST "$GATEWAY_URL/api/auth/login/seller" "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$PASSWORD\"}"
  [[ "$RESPONSE_STATUS" == "200" ]] || fail_test "판매자 로그인 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
  SELLER_TOKEN=$(jq -r '.accessToken' <<< "$RESPONSE_BODY")
  SELLER_ID=$(jq -r '.userId' <<< "$RESPONSE_BODY")

  http_json POST "$GATEWAY_URL/api/auth/signup/user" \
    "{\"email\":\"$USER_EMAIL\",\"password\":\"$PASSWORD\",\"name\":\"복구테스트사용자\"}"
  [[ "$RESPONSE_STATUS" == "201" ]] || fail_test "사용자 회원가입 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
  http_json POST "$GATEWAY_URL/api/auth/login/user" "{\"email\":\"$USER_EMAIL\",\"password\":\"$PASSWORD\"}"
  [[ "$RESPONSE_STATUS" == "200" ]] || fail_test "사용자 로그인 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
  USER_TOKEN=$(jq -r '.accessToken' <<< "$RESPONSE_BODY")
  USER_ID=$(jq -r '.userId' <<< "$RESPONSE_BODY")
}

create_test_product() {
  local ts="$1" stock="$2" price="${3:-12000}"
  local payload
  payload="{\"name\":\"복구 테스트 상품 $ts\",\"description\":\"장애 복구 검증용 상품\",\"price\":$price,\"stock\":$stock,\"category\":\"ELECTRONICS\",\"labels\":[]}"
  http_product_create "$SELLER_TOKEN" "$payload" "$SELLER_ID" "SELLER"
  [[ "$RESPONSE_STATUS" == "201" ]] || fail_test "상품 등록 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
  PRODUCT_ID=$(jq -r '.productId' <<< "$RESPONSE_BODY")
}

wait_for_db_value() {
  local db="$1" sql="$2" expected="$3" attempts="${4:-60}" interval="${5:-1}"
  local actual=""
  for _ in $(seq 1 "$attempts"); do
    actual=$(psql_value "$db" "$sql" 2>/dev/null || true)
    [[ "$actual" == "$expected" ]] && return 0
    sleep "$interval"
  done
  echo "[진단] DB 기대값 불일치: db=$db expected=$expected actual=$actual"
  return 1
}

wait_for_redis_value() {
  local key="$1" expected="$2" attempts="${3:-60}" interval="${4:-1}"
  local actual=""
  for _ in $(seq 1 "$attempts"); do
    actual=$(redis_cli GET "$key" || true)
    [[ "$actual" == "$expected" ]] && return 0
    sleep "$interval"
  done
  echo "[진단] Redis 기대값 불일치: key=$key expected=$expected actual=$actual"
  return 1
}

wait_for_order_status() {
  local order_id="$1" expected="$2" attempts="${3:-60}"
  for _ in $(seq 1 "$attempts"); do
    http_json GET "$GATEWAY_URL/api/orders/$order_id" "" "$USER_TOKEN" "$USER_ID" "USER"
    if [[ "$RESPONSE_STATUS" == "200" && "$(jq -r '.status' <<< "$RESPONSE_BODY")" == "$expected" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

cleanup_common_order_data() {
  local order_id="${ORDER_ID:-}" product_id="${PRODUCT_ID:-}"

  if [[ -n "$order_id" ]]; then
    psql_exec notification_service "delete from notifications where order_id='$order_id';"
    psql_exec settlement_service "delete from settlements where order_id='$order_id';"
    psql_exec shipping_service "delete from outbox_event where payload like '%$order_id%'; delete from shipping where order_id='$order_id';"
    psql_exec payment_service "delete from outbox_event where payload like '%$order_id%'; delete from payment_refund where order_id='$order_id'; delete from payment where order_id='$order_id';"
    psql_exec order_service "delete from order_saga_refund_receipt where saga_id in (select id from order_saga where order_id='$order_id'); delete from order_saga where order_id='$order_id'; delete from order_request_idempotency where order_id='$order_id' or user_id='${USER_ID:-00000000-0000-0000-0000-000000000000}'; delete from outbox_event where payload like '%$order_id%' or aggregate_id='$order_id'; delete from order_items where seller_order_id in (select id from seller_orders where order_id='$order_id'); delete from seller_orders where order_id='$order_id'; delete from orders where id='$order_id';"
  fi

  if [[ -n "$product_id" ]]; then
    psql_exec order_service "delete from product_read_model where product_id='$product_id';"
    psql_exec product_service "delete from outbox_event where aggregate_id='$product_id'; delete from product_labels where product_id='$product_id'; delete from product_images where product_id='$product_id'; delete from products where id='$product_id';"
    redis_del_pattern "{product:$product_id}:*"
    redis_cli SREM inventory:reservation-products "$product_id" >/dev/null || true
  fi

  if [[ -n "${USER_ID:-}" || -n "${SELLER_ID:-}" ]]; then
    psql_exec user_service "delete from audit_logs where user_id in ('${USER_ID:-00000000-0000-0000-0000-000000000000}','${SELLER_ID:-00000000-0000-0000-0000-000000000000}'); delete from users where id='${USER_ID:-00000000-0000-0000-0000-000000000000}' or email='${USER_EMAIL:-}'; delete from sellers where id='${SELLER_ID:-00000000-0000-0000-0000-000000000000}' or email='${SELLER_EMAIL:-}';"
    redis_del "refresh_token:user:${USER_ID:-}" "refresh_token:seller:${SELLER_ID:-}"
  fi
}
