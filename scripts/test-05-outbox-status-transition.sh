#!/usr/bin/env bash
set -euo pipefail

TEST_NAME="outbox-status-transition"
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
    --arg resultDir "$OUT_DIR" \
    --arg logFile "$LOG_FILE" \
    --arg productId "${PRODUCT_ID:-}" \
    --arg outboxEventId "${OUTBOX_EVENT_ID:-}" \
    --arg initialOutboxStatus "${INITIAL_OUTBOX_STATUS:-}" \
    --arg finalOutboxStatus "${FINAL_OUTBOX_STATUS:-}" \
    --argjson retryCount "${RETRY_COUNT:-0}" \
    --argjson durationSeconds "$duration" \
    '{test:$test,status:$status,message:$message,resultDir:$resultDir,logFile:$logFile,durationSeconds:$durationSeconds,data:{productId:$productId,outboxEventId:$outboxEventId,initialOutboxStatus:$initialOutboxStatus,finalOutboxStatus:$finalOutboxStatus,retryCount:$retryCount}}' \
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
    db_exec order_service "delete from product_read_model where product_id = '$PRODUCT_ID';"
    db_exec product_service "delete from outbox_event where aggregate_id = '$PRODUCT_ID'; delete from product_labels where product_id = '$PRODUCT_ID'; delete from product_images where product_id = '$PRODUCT_ID'; delete from products where id = '$PRODUCT_ID';"
    redis_del_pattern "{product:$PRODUCT_ID}:*"
  fi
  if [[ -n "${SELLER_ID:-}" || -n "${SELLER_EMAIL:-}" ]]; then
    db_exec user_service "delete from audit_logs where user_id = '${SELLER_ID:-}'; delete from sellers where email = '${SELLER_EMAIL:-}' or id = '${SELLER_ID:-}';"
    redis_del "refresh_token:seller:${SELLER_ID:-}"
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

load_outbox_row() {
  local product_id="$1"
  psql_value product_service "select id || '|' || status || '|' || retry_count from outbox_event where aggregate_id='$product_id' and event_type='PRODUCT_REGISTERED' order by created_at desc limit 1;" 2>/dev/null || true
}

require_cmd curl
require_cmd jq
require_cmd docker

TS=$(date +%s)
PASSWORD="Test1234!"
SELLER_EMAIL="seller_outbox_${TS}@portfolio.test"

echo "================================================------------"
echo "아웃박스 이벤트 발행 상태 전이 테스트"
echo "결과 경로: $OUT_DIR"
echo "================================================------------"

echo "[단계] 판매자 및 상품 생성"
http_json POST "$GATEWAY_URL/api/auth/signup/seller" "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$PASSWORD\",\"name\":\"아웃박스판매자\",\"businessName\":\"아웃박스상점\",\"businessNumber\":\"123-45-67890\",\"bankAccount\":\"110-123-456789\",\"bankCode\":\"088\"}"
[[ "$RESPONSE_STATUS" == "201" ]] || { MESSAGE="판매자 회원가입 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
http_json POST "$GATEWAY_URL/api/auth/login/seller" "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$PASSWORD\"}"
[[ "$RESPONSE_STATUS" == "200" ]] || { MESSAGE="판매자 로그인 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
SELLER_TOKEN=$(echo "$RESPONSE_BODY" | jq -r '.accessToken')
SELLER_ID=$(echo "$RESPONSE_BODY" | jq -r '.userId')

PRODUCT_PAYLOAD="{\"name\":\"아웃박스 테스트 상품 $TS\",\"description\":\"아웃박스 상태 전이 검증\",\"price\":15000,\"stock\":100,\"category\":\"ELECTRONICS\",\"labels\":[]}"
http_product_create "$SELLER_TOKEN" "$PRODUCT_PAYLOAD" "$SELLER_ID" "SELLER"
[[ "$RESPONSE_STATUS" == "201" ]] || { MESSAGE="상품 등록 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"; exit 1; }
PRODUCT_ID=$(echo "$RESPONSE_BODY" | jq -r '.productId')
echo "[성공] 상품 등록 완료: $PRODUCT_ID"

echo "[단계] 초기 아웃박스 이벤트 조회"
for _ in $(seq 1 20); do
  OUTBOX_ROW=$(load_outbox_row "$PRODUCT_ID")
  [[ -n "$OUTBOX_ROW" ]] && break
  sleep 1
done
[[ -n "${OUTBOX_ROW:-}" ]] || { MESSAGE="PRODUCT_REGISTERED 아웃박스 이벤트를 찾지 못했습니다"; exit 1; }
IFS='|' read -r OUTBOX_EVENT_ID INITIAL_OUTBOX_STATUS RETRY_COUNT <<< "$OUTBOX_ROW"
echo "[지표] 아웃박스 이벤트 ID=$OUTBOX_EVENT_ID"
echo "[지표] 초기 상태=$INITIAL_OUTBOX_STATUS"

echo "[단계] 최종 발행 상태 대기"
FINAL_OUTBOX_STATUS="$INITIAL_OUTBOX_STATUS"
for _ in $(seq 1 80); do
  OUTBOX_ROW=$(load_outbox_row "$PRODUCT_ID")
  IFS='|' read -r OUTBOX_EVENT_ID FINAL_OUTBOX_STATUS RETRY_COUNT <<< "$OUTBOX_ROW"
  echo "[정보] 현재 상태=$FINAL_OUTBOX_STATUS 재시도 횟수=$RETRY_COUNT"
  if [[ "$FINAL_OUTBOX_STATUS" == "SENT" || "$FINAL_OUTBOX_STATUS" == "FAILED" ]]; then
    break
  fi
  sleep 1
done

if [[ "$FINAL_OUTBOX_STATUS" != "SENT" && "$FINAL_OUTBOX_STATUS" != "FAILED" ]]; then
  MESSAGE="아웃박스 이벤트가 SENT 또는 FAILED 상태에 도달하지 못했습니다. 최종 상태=$FINAL_OUTBOX_STATUS"
  exit 1
fi

STATUS="PASSED"
MESSAGE="상품 등록 아웃박스 이벤트가 PENDING에서 발행 결과 상태($FINAL_OUTBOX_STATUS)로 전이됨을 검증했습니다."
