#!/usr/bin/env bash
set -euo pipefail

TEST_NAME="e2e-order-payment-flow"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATEWAY_URL="${GATEWAY_URL:-http://localhost}"
RESULT_ROOT="${RESULT_ROOT:-$SCRIPT_DIR/results}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)-$TEST_NAME}"
OUT_DIR="$RESULT_ROOT/$RUN_ID"
LOG_FILE="$OUT_DIR/run.log"
RESULT_FILE="$OUT_DIR/result.json"
mkdir -p "$OUT_DIR"
exec > >(tee -a "$LOG_FILE") 2>&1

START_EPOCH=$(date +%s)
STATUS="FAILED"
MESSAGE="테스트가 완료되지 않았습니다"
CLEANUP_DONE=0

finish() {
  local end_epoch duration
  local status_label
  end_epoch=$(date +%s)
  duration=$((end_epoch - START_EPOCH))
  [[ "$STATUS" == "PASSED" ]] && status_label="성공" || status_label="실패"
  jq -n \
    --arg test "$TEST_NAME" \
    --arg status "$STATUS" \
    --arg message "$MESSAGE" \
    --arg gatewayUrl "$GATEWAY_URL" \
    --arg resultDir "$OUT_DIR" \
    --arg logFile "$LOG_FILE" \
    --arg startedAt "$(date -d "@$START_EPOCH" --iso-8601=seconds 2>/dev/null || date -r "$START_EPOCH" '+%Y-%m-%dT%H:%M:%S%z')" \
    --arg endedAt "$(date -d "@$end_epoch" --iso-8601=seconds 2>/dev/null || date -r "$end_epoch" '+%Y-%m-%dT%H:%M:%S%z')" \
    --argjson durationSeconds "$duration" \
    --arg sellerEmail "${SELLER_EMAIL:-}" \
    --arg userEmail "${USER_EMAIL:-}" \
    --arg productId "${PRODUCT_ID:-}" \
    --arg orderId "${ORDER_ID:-}" \
    --arg finalOrderStatus "${FINAL_ORDER_STATUS:-}" \
    '{test:$test,status:$status,message:$message,gatewayUrl:$gatewayUrl,resultDir:$resultDir,logFile:$logFile,startedAt:$startedAt,endedAt:$endedAt,durationSeconds:$durationSeconds,data:{sellerEmail:$sellerEmail,userEmail:$userEmail,productId:$productId,orderId:$orderId,finalOrderStatus:$finalOrderStatus}}' \
    > "$RESULT_FILE"
  echo
  echo "[결과] $status_label - $MESSAGE"
  echo "[결과 파일] $RESULT_FILE"
  echo "[로그 파일] $LOG_FILE"
}

db_exec() {
  local db="$1" sql="$2"
  command -v docker >/dev/null 2>&1 || return 0
  docker exec eventful-postgres psql -U postgres -d "$db" -v ON_ERROR_STOP=0 -q -c "$sql" >/dev/null 2>&1 || true
}

redis_del() {
  command -v docker >/dev/null 2>&1 || return 0
  local key
  for key in "$@"; do
    [[ -n "$key" ]] || continue
    docker exec redis-node-1 redis-cli -c -p 7001 del "$key" >/dev/null 2>&1 || true
  done
}

psql_value() {
  local db="$1" sql="$2"
  command -v docker >/dev/null 2>&1 || return 1
  docker exec eventful-postgres psql -U postgres -d "$db" -tAc "$sql"
}

redis_cleanup() {
  if [[ -n "${PRODUCT_ID:-}" ]]; then
    redis_del \
      "{product:$PRODUCT_ID}:stock" \
      "{product:$PRODUCT_ID}:holdCount"
  fi
  if [[ -n "${USER_ID:-}" ]]; then
    redis_del "refresh_token:user:${USER_ID:-}"
  fi
  if [[ -n "${SELLER_ID:-}" ]]; then
    redis_del "refresh_token:seller:${SELLER_ID:-}"
  fi
}

cleanup() {
  [[ "$CLEANUP_DONE" == "1" ]] && return 0
  CLEANUP_DONE=1
  [[ "${KEEP_TEST_DATA:-0}" == "1" ]] && { echo "[정리] KEEP_TEST_DATA=1 설정으로 테스트 데이터 정리를 건너뜁니다"; return 0; }
  echo "[정리] 이번 실행에서 생성한 테스트 데이터를 삭제합니다"
  if [[ -n "${ORDER_ID:-}" ]]; then
    db_exec notification_service "delete from notifications where order_id = '$ORDER_ID';"
    db_exec shipping_service "delete from outbox_event where payload like '%$ORDER_ID%'; delete from shipping where order_id = '$ORDER_ID';"
    db_exec settlement_service "delete from settlements where order_id = '$ORDER_ID';"
    db_exec payment_service "delete from outbox_event where aggregate_id in (select id from payment_refund where order_id = '$ORDER_ID'); delete from payment_refund where order_id = '$ORDER_ID'; delete from outbox_event where aggregate_id in (select id from payment where order_id = '$ORDER_ID'); delete from payment where order_id = '$ORDER_ID';"
    db_exec order_service "delete from outbox_event where aggregate_id = '$ORDER_ID'; delete from order_items where seller_order_id in (select id from seller_orders where order_id = '$ORDER_ID'); delete from seller_orders where order_id = '$ORDER_ID'; delete from orders where id = '$ORDER_ID';"
  fi
  if [[ -n "${PRODUCT_ID:-}" ]]; then
    db_exec order_service "delete from product_read_model where product_id = '$PRODUCT_ID';"
    db_exec product_service "delete from outbox_event where aggregate_id = '$PRODUCT_ID'; delete from product_labels where product_id = '$PRODUCT_ID'; delete from product_images where product_id = '$PRODUCT_ID'; delete from products where id = '$PRODUCT_ID';"
  fi
  if [[ -n "${USER_ID:-}" || -n "${SELLER_ID:-}" || -n "${USER_EMAIL:-}" || -n "${SELLER_EMAIL:-}" ]]; then
    db_exec user_service "delete from audit_logs where user_id in ('${USER_ID:-}', '${SELLER_ID:-}'); delete from users where email = '${USER_EMAIL:-}' or id = '${USER_ID:-}'; delete from sellers where email = '${SELLER_EMAIL:-}' or id = '${SELLER_ID:-}';"
  fi
  redis_cleanup
  echo "[정리] 테스트 데이터 정리 완료"
}

on_exit() {
  local code=$?
  cleanup
  finish
  exit "$code"
}
on_interrupt() {
  MESSAGE="테스트가 인터럽트되어 중단되었습니다"
  exit 130
}
trap on_exit EXIT
trap on_interrupt INT TERM

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    MESSAGE="필수 명령어가 없습니다: $1"
    exit 1
  }
}

http_json() {
  local method="$1" url="$2" data="${3:-}" token="${4:-}"
  local user_id="${5:-}" role="${6:-}"
  local body_file status
  body_file=$(mktemp)
  if [[ -n "$token" ]]; then
    status=$(curl -sS -o "$body_file" -w "%{http_code}" -X "$method" "$url" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $token" \
      ${user_id:+-H "X-User-Id: $user_id"} \
      ${role:+-H "X-User-Role: $role"} \
      ${data:+-d "$data"} || printf "000")
  else
    status=$(curl -sS -o "$body_file" -w "%{http_code}" -X "$method" "$url" \
      -H "Content-Type: application/json" \
      ${data:+-d "$data"} || printf "000")
  fi
  status="${status: -3}"
  RESPONSE_STATUS="$status"
  RESPONSE_BODY=$(cat "$body_file")
  rm -f "$body_file"
}

http_product_create() {
  local token="$1" payload="$2" user_id="${3:-}" role="${4:-}"
  local body_file status
  body_file=$(mktemp)
  status=$(curl -sS -o "$body_file" -w "%{http_code}" -X POST "$GATEWAY_URL/api/products" \
    -H "Authorization: Bearer $token" \
    ${user_id:+-H "X-User-Id: $user_id"} \
    ${role:+-H "X-User-Role: $role"} \
    -F "request=$payload;type=application/json" || printf "000")
  status="${status: -3}"
  RESPONSE_STATUS="$status"
  RESPONSE_BODY=$(cat "$body_file")
  rm -f "$body_file"
}

wait_order_status() {
  local expected="$1" order_id="$2" token="$3" user_id="$4" role="$5" attempts="${6:-40}"
  for _ in $(seq 1 "$attempts"); do
    http_json GET "$GATEWAY_URL/api/orders/$order_id" "" "$token" "$user_id" "$role"
    if [[ "$RESPONSE_STATUS" == "200" ]]; then
      FINAL_ORDER_STATUS=$(echo "$RESPONSE_BODY" | jq -r '.status')
      echo "[정보] 주문 상태: $FINAL_ORDER_STATUS"
      [[ "$FINAL_ORDER_STATUS" == "$expected" ]] && return 0
    else
      echo "[정보] 주문 상태 조회 재시도: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
    fi
    sleep 1
  done
  return 1
}

wait_payment_record() {
  local order_id="$1" attempts="${2:-60}"
  if ! command -v docker >/dev/null 2>&1; then
    echo "[정보] Docker를 찾을 수 없어 고정 대기 시간으로 대체합니다"
    sleep "${PAYMENT_WAIT_SECONDS:-10}"
    return 0
  fi

  for _ in $(seq 1 "$attempts"); do
    local count
    count=$(psql_value payment_service "select count(*) from payment where order_id='$order_id';" 2>/dev/null || echo 0)
    echo "[정보] 결제 예약 레코드 수: $count"
    [[ "$count" == "1" ]] && return 0
    sleep 1
  done
  return 1
}

require_cmd curl
require_cmd jq

TS=$(date +%s)
SELLER_EMAIL="seller_${TS}@portfolio.test"
USER_EMAIL="user_${TS}@portfolio.test"
PASSWORD="Test1234!"

echo "============================================================"
echo "E2E 정상 플로우 테스트"
echo "진입점: $GATEWAY_URL"
echo "결과 경로:  $OUT_DIR"
echo "============================================================"

echo "[단계] 판매자 회원가입 및 로그인"
http_json POST "$GATEWAY_URL/api/auth/signup/seller" \
  "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$PASSWORD\",\"name\":\"테스트판매자\",\"businessName\":\"포트폴리오상점\",\"businessNumber\":\"123-45-67890\",\"bankAccount\":\"110-123-456789\",\"bankCode\":\"088\"}"
[[ "$RESPONSE_STATUS" == "201" ]] || { MESSAGE="판매자 회원가입 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
http_json POST "$GATEWAY_URL/api/auth/login/seller" "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$PASSWORD\"}"
[[ "$RESPONSE_STATUS" == "200" ]] || { MESSAGE="판매자 로그인 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
SELLER_TOKEN=$(echo "$RESPONSE_BODY" | jq -r '.accessToken')
SELLER_ID=$(echo "$RESPONSE_BODY" | jq -r '.userId')
echo "[성공] 판매자 로그인 완료"

echo "[단계] 사용자 회원가입 및 로그인"
http_json POST "$GATEWAY_URL/api/auth/signup/user" "{\"email\":\"$USER_EMAIL\",\"password\":\"$PASSWORD\",\"name\":\"테스트사용자\"}"
[[ "$RESPONSE_STATUS" == "201" ]] || { MESSAGE="사용자 회원가입 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
http_json POST "$GATEWAY_URL/api/auth/login/user" "{\"email\":\"$USER_EMAIL\",\"password\":\"$PASSWORD\"}"
[[ "$RESPONSE_STATUS" == "200" ]] || { MESSAGE="사용자 로그인 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
USER_TOKEN=$(echo "$RESPONSE_BODY" | jq -r '.accessToken')
USER_ID=$(echo "$RESPONSE_BODY" | jq -r '.userId')
echo "[성공] 사용자 로그인 완료"

echo "[단계] 상품 등록"
PRODUCT_PAYLOAD="{\"name\":\"E2E 테스트 상품 $TS\",\"description\":\"E2E 정상 플로우 검증용 상품\",\"price\":29000,\"stock\":50,\"category\":\"ELECTRONICS\",\"labels\":[]}"
http_product_create "$SELLER_TOKEN" "$PRODUCT_PAYLOAD" "$SELLER_ID" "SELLER"
[[ "$RESPONSE_STATUS" == "201" ]] || { MESSAGE="상품 등록 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
PRODUCT_ID=$(echo "$RESPONSE_BODY" | jq -r '.productId')
echo "[성공] 상품 등록 완료: $PRODUCT_ID"

echo "[단계] 상품 등록 이벤트 전파 대기"
sleep "${EVENT_WAIT_SECONDS:-8}"

echo "[단계] 주문 생성"
ORDER_PAYLOAD="{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":2}]}"
for attempt in $(seq 1 20); do
  http_json POST "$GATEWAY_URL/api/orders" "$ORDER_PAYLOAD" "$USER_TOKEN" "$USER_ID" "USER"
  if [[ "$RESPONSE_STATUS" == "200" ]]; then
    ORDER_ID=$(echo "$RESPONSE_BODY" | jq -r '.orderId')
    break
  fi
  echo "[정보] 주문 생성 재시도 $attempt: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
  sleep 1
done
[[ -n "${ORDER_ID:-}" && "$ORDER_ID" != "null" ]] || { MESSAGE="주문 생성 재시도 초과"; exit 1; }
echo "[성공] 주문 예약 완료: $ORDER_ID"

echo "[단계] 결제 예약 생성 대기"
if ! wait_payment_record "$ORDER_ID" 60; then
  MESSAGE="ORDER_RESERVED 이벤트 기반 결제 예약이 생성되지 않았습니다"
  exit 1
fi

echo "[단계] 결제 성공 웹훅 호출"
http_json POST "$GATEWAY_URL/api/payments/webhook" "{\"orderId\":\"$ORDER_ID\",\"result\":\"SUCCESS\",\"pgTxId\":\"PG-$TS\",\"amount\":58000}"
[[ "$RESPONSE_STATUS" == "200" ]] || { MESSAGE="결제 웹훅 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
echo "[성공] 결제 웹훅 처리 완료"

echo "[단계] 주문 확정 상태 대기"
if ! wait_order_status "ORDER_CONFIRMED" "$ORDER_ID" "$USER_TOKEN" "$USER_ID" "USER" 60; then
  MESSAGE="주문이 확정 상태로 변경되지 않았습니다"
  exit 1
fi

STATUS="PASSED"
MESSAGE="상품 등록부터 주문 생성, 결제 성공, 주문 확정까지 정상 E2E 플로우를 검증했습니다."
