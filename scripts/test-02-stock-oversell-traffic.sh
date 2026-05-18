#!/usr/bin/env bash
set -euo pipefail

TEST_NAME="stock-oversell-traffic"
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
STOCK_LIMIT="${STOCK_LIMIT:-1000}"
STOCK_REQUESTS="${STOCK_REQUESTS:-5000}"
STOCK_CONCURRENCY="${STOCK_CONCURRENCY:-400}"

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
    --arg resultDir "$OUT_DIR" \
    --arg logFile "$LOG_FILE" \
    --arg productId "${PRODUCT_ID:-}" \
    --argjson stockLimit "$STOCK_LIMIT" \
    --argjson requests "$STOCK_REQUESTS" \
    --argjson concurrency "$STOCK_CONCURRENCY" \
    --argjson successCount "${SUCCESS_COUNT:-0}" \
    --argjson failureCount "${FAILURE_COUNT:-0}" \
    --argjson oversellCount "${OVERSELL_COUNT:-0}" \
    --arg finalRedisStock "${FINAL_REDIS_STOCK:-unknown}" \
    --argjson durationSeconds "$duration" \
    '{test:$test,status:$status,message:$message,resultDir:$resultDir,logFile:$logFile,durationSeconds:$durationSeconds,data:{productId:$productId,stockLimit:$stockLimit,requests:$requests,concurrency:$concurrency,successCount:$successCount,failureCount:$failureCount,oversellCount:$oversellCount,finalRedisStock:$finalRedisStock}}' \
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

redis_del_pattern() {
  command -v docker >/dev/null 2>&1 || return 0
  local pattern="$1"
  local key
  while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    redis_del "$key"
  done < <(docker exec redis-node-1 redis-cli -c -p 7001 --scan --pattern "$pattern" 2>/dev/null || true)
}

cleanup() {
  [[ "$CLEANUP_DONE" == "1" ]] && return 0
  CLEANUP_DONE=1
  [[ "${KEEP_TEST_DATA:-0}" == "1" ]] && { echo "[정리] KEEP_TEST_DATA=1 설정으로 테스트 데이터 정리를 건너뜁니다"; return 0; }
  echo "[정리] 이번 실행에서 생성한 테스트 데이터를 삭제합니다"
  if [[ -n "${PRODUCT_ID:-}" ]]; then
    db_exec notification_service "delete from notifications where user_id = '${USER_ID:-}';"
    db_exec shipping_service "delete from shipping where user_id = '${USER_ID:-}';"
    db_exec settlement_service "delete from settlements where user_id = '${USER_ID:-}' or seller_id = '${SELLER_ID:-}';"
    db_exec payment_service "delete from outbox_event where aggregate_id in (select id from payment_refund where order_id in (select order_id from payment where user_id = '${USER_ID:-}')); delete from payment_refund where order_id in (select order_id from payment where user_id = '${USER_ID:-}'); delete from outbox_event where aggregate_id in (select id from payment where user_id = '${USER_ID:-}'); delete from payment where user_id = '${USER_ID:-}';"
    db_exec order_service "delete from outbox_event where aggregate_id in (select id from orders where user_id = '${USER_ID:-}'); delete from order_items where seller_order_id in (select id from seller_orders where order_id in (select id from orders where user_id = '${USER_ID:-}')); delete from seller_orders where order_id in (select id from orders where user_id = '${USER_ID:-}'); delete from orders where user_id = '${USER_ID:-}'; delete from product_read_model where product_id = '$PRODUCT_ID';"
    db_exec product_service "delete from outbox_event where aggregate_id = '$PRODUCT_ID'; delete from product_labels where product_id = '$PRODUCT_ID'; delete from product_images where product_id = '$PRODUCT_ID'; delete from products where id = '$PRODUCT_ID';"
    redis_del_pattern "{product:$PRODUCT_ID}:*"
  fi
  if [[ -n "${USER_ID:-}" || -n "${SELLER_ID:-}" || -n "${USER_EMAIL:-}" || -n "${SELLER_EMAIL:-}" ]]; then
    db_exec user_service "delete from audit_logs where user_id in ('${USER_ID:-}', '${SELLER_ID:-}'); delete from users where email = '${USER_EMAIL:-}' or id = '${USER_ID:-}'; delete from sellers where email = '${SELLER_EMAIL:-}' or id = '${SELLER_ID:-}';"
    redis_del "refresh_token:user:${USER_ID:-}" "refresh_token:seller:${SELLER_ID:-}"
  fi
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

wait_product_read_model() {
  local product_id="$1"
  if ! command -v docker >/dev/null 2>&1; then
    sleep "${EVENT_WAIT_SECONDS:-10}"
    return 0
  fi
  for _ in $(seq 1 40); do
    local count
    count=$(docker exec eventful-postgres psql -U postgres -d order_service -tAc "select count(*) from product_read_model where product_id='$product_id';" 2>/dev/null || true)
    if [[ "$count" == "1" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

require_cmd curl
require_cmd jq
require_cmd xargs

TS=$(date +%s)
PASSWORD="Test1234!"
SELLER_EMAIL="seller_stock_${TS}@portfolio.test"
USER_EMAIL="user_stock_${TS}@portfolio.test"

echo "================================================------------"
echo "대용량 재고 초과 주문 동시성 테스트"
echo "요청 수: $STOCK_REQUESTS, 동시성: $STOCK_CONCURRENCY, 초기 재고: $STOCK_LIMIT"
echo "결과 경로:   $OUT_DIR"
echo "================================================------------"

echo "[단계] 판매자/사용자/상품 생성"
http_json POST "$GATEWAY_URL/api/auth/signup/seller" "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$PASSWORD\",\"name\":\"부하판매자\",\"businessName\":\"부하상점\",\"businessNumber\":\"123-45-67890\",\"bankAccount\":\"110-123-456789\",\"bankCode\":\"088\"}"
[[ "$RESPONSE_STATUS" == "201" ]] || { MESSAGE="판매자 회원가입 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
http_json POST "$GATEWAY_URL/api/auth/login/seller" "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$PASSWORD\"}"
[[ "$RESPONSE_STATUS" == "200" ]] || { MESSAGE="판매자 로그인 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
SELLER_TOKEN=$(echo "$RESPONSE_BODY" | jq -r '.accessToken')
SELLER_ID=$(echo "$RESPONSE_BODY" | jq -r '.userId')

http_json POST "$GATEWAY_URL/api/auth/signup/user" "{\"email\":\"$USER_EMAIL\",\"password\":\"$PASSWORD\",\"name\":\"부하사용자\"}"
[[ "$RESPONSE_STATUS" == "201" ]] || { MESSAGE="사용자 회원가입 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
http_json POST "$GATEWAY_URL/api/auth/login/user" "{\"email\":\"$USER_EMAIL\",\"password\":\"$PASSWORD\"}"
[[ "$RESPONSE_STATUS" == "200" ]] || { MESSAGE="사용자 로그인 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
USER_TOKEN=$(echo "$RESPONSE_BODY" | jq -r '.accessToken')
USER_ID=$(echo "$RESPONSE_BODY" | jq -r '.userId')

PRODUCT_PAYLOAD="{\"name\":\"재고 초과 테스트 상품 $TS\",\"description\":\"대용량 동시 주문 검증\",\"price\":10000,\"stock\":$STOCK_LIMIT,\"category\":\"ELECTRONICS\",\"labels\":[]}"
http_product_create "$SELLER_TOKEN" "$PRODUCT_PAYLOAD" "$SELLER_ID" "SELLER"
[[ "$RESPONSE_STATUS" == "201" ]] || { MESSAGE="상품 등록 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
PRODUCT_ID=$(echo "$RESPONSE_BODY" | jq -r '.productId')
echo "[성공] 상품 등록 완료: $PRODUCT_ID"

echo "[단계] 주문 서비스 상품 읽기 모델 동기화 대기"
wait_product_read_model "$PRODUCT_ID" || { MESSAGE="상품 읽기 모델이 주문 서비스에 동기화되지 않았습니다"; exit 1; }

echo "[단계] 대량 동시 주문 요청 실행"
export GATEWAY_URL PRODUCT_ID USER_TOKEN USER_ID
RESULT_LINES=$(seq 1 "$STOCK_REQUESTS" | xargs -P "$STOCK_CONCURRENCY" -I {} bash -c '
  payload="{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1}]}"
  response=$(curl -sS -w "\n%{http_code}" -X POST "$GATEWAY_URL/api/orders" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H "X-User-Id: $USER_ID" \
    -H "X-User-Role: USER" \
    -d "$payload" || printf "\n000")
  status="${response##*$'\''\n'\''}"
  body="${response%$'\''\n'\''*}"
  order_id=$(printf "%s" "$body" | jq -r ".orderId // null" 2>/dev/null || echo null)
  if [[ "$status" == "200" && "$order_id" != "null" ]]; then
    echo "success"
  else
    echo "failure"
  fi
' _ {})

SUCCESS_COUNT=$(printf "%s\n" "$RESULT_LINES" | awk '$0=="success"{c++} END{print c+0}')
FAILURE_COUNT=$(printf "%s\n" "$RESULT_LINES" | awk '$0=="failure"{c++} END{print c+0}')
OVERSELL_COUNT=$((SUCCESS_COUNT > STOCK_LIMIT ? SUCCESS_COUNT - STOCK_LIMIT : 0))
EXPECTED_SUCCESS_COUNT=$((STOCK_REQUESTS < STOCK_LIMIT ? STOCK_REQUESTS : STOCK_LIMIT))
EXPECTED_FINAL_REDIS_STOCK=$((STOCK_LIMIT - EXPECTED_SUCCESS_COUNT))

if command -v docker >/dev/null 2>&1; then
  FINAL_REDIS_STOCK=$(docker exec redis-node-1 redis-cli -c -p 7001 get "{product:$PRODUCT_ID}:stock" 2>/dev/null || echo "unknown")
else
  FINAL_REDIS_STOCK="docker-not-found"
fi

echo "[지표] 주문 성공 수=$SUCCESS_COUNT"
echo "[지표] 주문 실패 수=$FAILURE_COUNT"
echo "[지표] 초과 판매 수=$OVERSELL_COUNT"
echo "[지표] 최종 Redis 가용 재고=$FINAL_REDIS_STOCK"

if (( OVERSELL_COUNT > 0 )); then
  MESSAGE="초과 판매 발생: success=$SUCCESS_COUNT stock=$STOCK_LIMIT"
  exit 1
fi
if (( SUCCESS_COUNT != EXPECTED_SUCCESS_COUNT )); then
  MESSAGE="성공 주문 수가 기대값과 다릅니다. expected=$EXPECTED_SUCCESS_COUNT actual=$SUCCESS_COUNT"
  exit 1
fi
if [[ "$FINAL_REDIS_STOCK" =~ ^[0-9]+$ ]] && (( FINAL_REDIS_STOCK != EXPECTED_FINAL_REDIS_STOCK )); then
  MESSAGE="최종 Redis 재고가 기대값과 다릅니다. expected=$EXPECTED_FINAL_REDIS_STOCK actual=$FINAL_REDIS_STOCK"
  exit 1
fi

STATUS="PASSED"
MESSAGE="대용량 동시 주문에서도 성공 주문 수가 초기 재고를 초과하지 않아 Redis Lua 재고 방어를 검증했습니다."
