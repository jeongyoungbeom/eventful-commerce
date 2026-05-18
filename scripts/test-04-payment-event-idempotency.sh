#!/usr/bin/env bash
set -euo pipefail

TEST_NAME="payment-event-idempotency"
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
DUPLICATE_EVENTS="${DUPLICATE_EVENTS:-3000}"

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
    --arg orderId "${ORDER_ID:-}" \
    --arg paymentEventId "${PAYMENT_EVENT_ID:-}" \
    --arg finalOrderStatus "${FINAL_ORDER_STATUS:-}" \
    --argjson duplicateEvents "$DUPLICATE_EVENTS" \
    --argjson processedEventCount "${PROCESSED_EVENT_COUNT:-0}" \
    --argjson orderConfirmedEventCount "${ORDER_CONFIRMED_EVENT_COUNT:-0}" \
    --argjson durationSeconds "$duration" \
    '{test:$test,status:$status,message:$message,resultDir:$resultDir,logFile:$logFile,durationSeconds:$durationSeconds,data:{productId:$productId,orderId:$orderId,paymentEventId:$paymentEventId,duplicateEvents:$duplicateEvents,processedEventCount:$processedEventCount,orderConfirmedEventCount:$orderConfirmedEventCount,finalOrderStatus:$finalOrderStatus}}' \
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
  if [[ -n "${ORDER_ID:-}" ]]; then
    db_exec notification_service "delete from notifications where order_id = '$ORDER_ID'; delete from processed_event where event_id = '${PAYMENT_EVENT_ID:-}';"
    db_exec shipping_service "delete from outbox_event where payload like '%$ORDER_ID%'; delete from shipping where order_id = '$ORDER_ID'; delete from processed_event where event_id = '${PAYMENT_EVENT_ID:-}';"
    db_exec settlement_service "delete from processed_event where event_id = '${PAYMENT_EVENT_ID:-}'; delete from settlements where order_id = '$ORDER_ID';"
    db_exec payment_service "delete from outbox_event where aggregate_id in (select id from payment_refund where order_id = '$ORDER_ID'); delete from payment_refund where order_id = '$ORDER_ID'; delete from outbox_event where aggregate_id in (select id from payment where order_id = '$ORDER_ID'); delete from payment where order_id = '$ORDER_ID';"
    db_exec order_service "delete from processed_event where event_id = '${PAYMENT_EVENT_ID:-}'; delete from outbox_event where aggregate_id = '$ORDER_ID'; delete from order_items where seller_order_id in (select id from seller_orders where order_id = '$ORDER_ID'); delete from seller_orders where order_id = '$ORDER_ID'; delete from orders where id = '$ORDER_ID';"
  fi
  if [[ -n "${PRODUCT_ID:-}" ]]; then
    db_exec order_service "delete from product_read_model where product_id = '$PRODUCT_ID';"
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

psql_value() {
  local db="$1" sql="$2"
  docker exec eventful-postgres psql -U postgres -d "$db" -tAc "$sql"
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

wait_payment_event() {
  local order_id="$1"
  for _ in $(seq 1 60); do
    PAYMENT_EVENT_ROW=$(psql_value payment_service "select id || '|' || aggregate_type || '|' || aggregate_id || '|' || event_type || '|' || replace(payload, E'\n', '') || '|' || created_at from outbox_event where event_type='PAYMENT_COMPLETED' and payload like '%$order_id%' order by created_at desc limit 1;" 2>/dev/null || true)
    if [[ -n "$PAYMENT_EVENT_ROW" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_payment_record() {
  local order_id="$1" attempts="${2:-60}"
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
require_cmd docker

TS=$(date +%s)
PASSWORD="Test1234!"
SELLER_EMAIL="seller_idem_${TS}@portfolio.test"
USER_EMAIL="user_idem_${TS}@portfolio.test"

echo "================================================------------"
echo "결제 완료 중복 이벤트 멱등성 테스트"
echo "중복 이벤트 수: $DUPLICATE_EVENTS"
echo "결과 경로:           $OUT_DIR"
echo "================================================------------"

echo "[단계] 판매자/사용자/상품/주문 생성"
http_json POST "$GATEWAY_URL/api/auth/signup/seller" "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$PASSWORD\",\"name\":\"멱등판매자\",\"businessName\":\"멱등상점\",\"businessNumber\":\"123-45-67890\",\"bankAccount\":\"110-123-456789\",\"bankCode\":\"088\"}"
[[ "$RESPONSE_STATUS" == "201" ]] || { MESSAGE="판매자 회원가입 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
http_json POST "$GATEWAY_URL/api/auth/login/seller" "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$PASSWORD\"}"
[[ "$RESPONSE_STATUS" == "200" ]] || { MESSAGE="판매자 로그인 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
SELLER_TOKEN=$(echo "$RESPONSE_BODY" | jq -r '.accessToken')
SELLER_ID=$(echo "$RESPONSE_BODY" | jq -r '.userId')

http_json POST "$GATEWAY_URL/api/auth/signup/user" "{\"email\":\"$USER_EMAIL\",\"password\":\"$PASSWORD\",\"name\":\"멱등사용자\"}"
[[ "$RESPONSE_STATUS" == "201" ]] || { MESSAGE="사용자 회원가입 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
http_json POST "$GATEWAY_URL/api/auth/login/user" "{\"email\":\"$USER_EMAIL\",\"password\":\"$PASSWORD\"}"
[[ "$RESPONSE_STATUS" == "200" ]] || { MESSAGE="사용자 로그인 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
USER_TOKEN=$(echo "$RESPONSE_BODY" | jq -r '.accessToken')
USER_ID=$(echo "$RESPONSE_BODY" | jq -r '.userId')

PRODUCT_PAYLOAD="{\"name\":\"멱등성 테스트 상품 $TS\",\"description\":\"중복 이벤트 검증\",\"price\":12000,\"stock\":20,\"category\":\"ELECTRONICS\",\"labels\":[]}"
http_product_create "$SELLER_TOKEN" "$PRODUCT_PAYLOAD" "$SELLER_ID" "SELLER"
[[ "$RESPONSE_STATUS" == "201" ]] || { MESSAGE="상품 등록 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
PRODUCT_ID=$(echo "$RESPONSE_BODY" | jq -r '.productId')
sleep "${EVENT_WAIT_SECONDS:-8}"

ORDER_PAYLOAD="{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1}]}"
for attempt in $(seq 1 20); do
  http_json POST "$GATEWAY_URL/api/orders" "$ORDER_PAYLOAD" "$USER_TOKEN" "$USER_ID" "USER"
  if [[ "$RESPONSE_STATUS" == "200" ]]; then
    ORDER_ID=$(echo "$RESPONSE_BODY" | jq -r '.orderId')
    break
  fi
  echo "[정보] 주문 생성 재시도 $attempt: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
  sleep 1
done
[[ -n "${ORDER_ID:-}" && "$ORDER_ID" != "null" ]] || { MESSAGE="주문 생성 실패"; exit 1; }

echo "[단계] 결제 예약 생성 대기"
if ! wait_payment_record "$ORDER_ID" 60; then
  MESSAGE="ORDER_RESERVED 이벤트 기반 결제 예약이 생성되지 않았습니다"
  exit 1
fi

echo "[단계] 결제 성공 웹훅 호출"
http_json POST "$GATEWAY_URL/api/payments/webhook" "{\"orderId\":\"$ORDER_ID\",\"result\":\"SUCCESS\",\"pgTxId\":\"PG-IDEM-$TS\",\"amount\":12000}"
[[ "$RESPONSE_STATUS" == "200" ]] || { MESSAGE="결제 웹훅 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }

echo "[단계] PAYMENT_COMPLETED 아웃박스 메시지 조회"
wait_payment_event "$ORDER_ID" || { MESSAGE="PAYMENT_COMPLETED 아웃박스 이벤트를 찾지 못했습니다"; exit 1; }
IFS='|' read -r PAYMENT_EVENT_ID AGGREGATE_TYPE AGGREGATE_ID EVENT_TYPE PAYLOAD OCCURRED_AT <<< "$PAYMENT_EVENT_ROW"
PAYMENT_EVENT_MESSAGE=$(jq -cn \
  --arg eventId "$PAYMENT_EVENT_ID" \
  --arg aggregateType "$AGGREGATE_TYPE" \
  --arg aggregateId "$AGGREGATE_ID" \
  --arg eventType "$EVENT_TYPE" \
  --arg occurredAt "$OCCURRED_AT" \
  --arg payload "$PAYLOAD" \
  '{eventId:$eventId,aggregateType:$aggregateType,aggregateId:$aggregateId,eventType:$eventType,occurredAt:$occurredAt,payload:$payload}')
echo "$PAYMENT_EVENT_MESSAGE" > "$OUT_DIR/payment-completed-message.json"
echo "[성공] 결제 이벤트 ID: $PAYMENT_EVENT_ID"

echo "[단계] Kafka에 중복 결제 이벤트 발행"
for _ in $(seq 1 "$DUPLICATE_EVENTS"); do
  echo "$PAYMENT_EVENT_MESSAGE"
done | docker exec -i eventful-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payment-events >/dev/null

echo "[단계] 이벤트 소비 대기"
sleep "${DUPLICATE_WAIT_SECONDS:-15}"

http_json GET "$GATEWAY_URL/api/orders/$ORDER_ID" "" "$USER_TOKEN" "$USER_ID" "USER"
[[ "$RESPONSE_STATUS" == "200" ]] || { MESSAGE="주문 조회 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
FINAL_ORDER_STATUS=$(echo "$RESPONSE_BODY" | jq -r '.status')
PROCESSED_EVENT_COUNT=$(psql_value order_service "select count(*) from processed_event where event_id='$PAYMENT_EVENT_ID';")
ORDER_CONFIRMED_EVENT_COUNT=$(psql_value order_service "select count(*) from outbox_event where aggregate_id='$ORDER_ID' and event_type='ORDER_CONFIRMED';")

echo "[지표] 처리 완료 이벤트 수=$PROCESSED_EVENT_COUNT"
echo "[지표] 주문 확정 아웃박스 이벤트 수=$ORDER_CONFIRMED_EVENT_COUNT"
echo "[지표] 최종 주문 상태=$FINAL_ORDER_STATUS"

if [[ "$FINAL_ORDER_STATUS" != "ORDER_CONFIRMED" ]]; then
  MESSAGE="최종 주문 상태가 ORDER_CONFIRMED가 아닙니다. 실제 상태: $FINAL_ORDER_STATUS"
  exit 1
fi
if [[ "$PROCESSED_EVENT_COUNT" != "1" ]]; then
  MESSAGE="processed_event 수가 1건이 아닙니다. 실제 수: $PROCESSED_EVENT_COUNT"
  exit 1
fi
if [[ "$ORDER_CONFIRMED_EVENT_COUNT" != "1" ]]; then
  MESSAGE="ORDER_CONFIRMED 아웃박스 이벤트 수가 1건이 아닙니다. 실제 수: $ORDER_CONFIRMED_EVENT_COUNT"
  exit 1
fi

STATUS="PASSED"
MESSAGE="동일 PAYMENT_COMPLETED 이벤트를 대량 재주입해도 주문 확정과 후속 아웃박스 이벤트가 1회만 처리됨을 검증했습니다."
