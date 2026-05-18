#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_DIR="$ROOT_DIR/scripts"
MODE="${1:-scenario}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost}"
RESULT_ROOT="${RESULT_ROOT:-$SCRIPT_DIR/results}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)-verify-$MODE}"
OUT_DIR="$RESULT_ROOT/$RUN_ID"
LOG_FILE="$OUT_DIR/run.log"
SUMMARY_FILE="$OUT_DIR/summary.md"
RESULT_FILE="$OUT_DIR/result.json"

mkdir -p "$OUT_DIR"
exec > >(tee -a "$LOG_FILE") 2>&1

START_EPOCH=$(date +%s)
OVERALL_STATUS="PASSED"

NAMES=()
STATUSES=()
DURATIONS=()
MESSAGES=()
RESULT_FILES=()
LOG_FILES=()

usage() {
  cat <<'USAGE'
Usage: ./scripts/verify.sh [scenario|gradle|all]

Modes:
  scenario  Run portfolio scenario scripts under scripts/test-*.sh. Default.
  gradle    Run Gradle unit/build verification with ./gradlew test.
  all       Run Gradle verification, then portfolio scenario scripts.

Environment:
  GATEWAY_URL   API gateway URL. Default: http://localhost
  RESULT_ROOT   Directory for generated logs and summaries. Default: scripts/results
USAGE
}

iso_time() {
  local epoch="$1"
  date -d "@$epoch" --iso-8601=seconds 2>/dev/null || date -r "$epoch" '+%Y-%m-%dT%H:%M:%S%z'
}

duration_since() {
  local start="$1"
  echo "$(($(date +%s) - start))"
}

record_result() {
  local name="$1" status="$2" duration="$3" message="$4" result_file="$5" log_file="$6"
  NAMES+=("$name")
  STATUSES+=("$status")
  DURATIONS+=("$duration")
  MESSAGES+=("$message")
  RESULT_FILES+=("$result_file")
  LOG_FILES+=("$log_file")

  if [[ "$status" != "PASSED" ]]; then
    OVERALL_STATUS="FAILED"
  fi
}

json_value() {
  local file="$1" filter="$2" fallback="$3"
  if [[ -f "$file" ]] && command -v jq >/dev/null 2>&1; then
    local value
    value=$(jq -r "$filter // empty" "$file" 2>/dev/null | sed '/^$/d' | head -n 1)
    [[ -n "$value" ]] && printf '%s\n' "$value" || printf '%s\n' "$fallback"
  else
    printf '%s\n' "$fallback"
  fi
}

comma_number() {
  local value="$1"
  if [[ ! "$value" =~ ^[0-9]+$ ]]; then
    printf '%s' "$value"
    return 0
  fi
  printf '%s' "$value" | rev | sed 's/.../&,/g;s/,$//' | rev
}

check_title() {
  local name="$1"
  case "$name" in
    gradle-test) printf 'Gradle 테스트' ;;
    test-01-e2e-order-payment-flow) printf '주문/결제 E2E' ;;
    test-02-stock-oversell-traffic) printf '재고 초과 판매 방지' ;;
    test-03-order-cancel-lock-traffic) printf '주문 취소 중복 요청 방어' ;;
    test-04-payment-event-idempotency) printf '결제 이벤트 멱등성' ;;
    test-05-outbox-status-transition) printf 'Outbox 상태 전이' ;;
    *) printf '%s' "$name" ;;
  esac
}

print_capture_summary() {
  local total_duration="$1" passed="$2" failed="$3"

  echo "============================================================"
  echo "Eventful Commerce 검증 결과"
  echo "${passed} passed / ${failed} failed · 실행 시간 ${total_duration}초 · mode=${MODE}"
  echo "============================================================"

  for i in "${!NAMES[@]}"; do
    local name="${NAMES[$i]}"
    local status="${STATUSES[$i]}"
    local duration="${DURATIONS[$i]}"
    local result_file="${RESULT_FILES[$i]}"
    local title
    title="$(check_title "$name")"

    case "$name" in
      gradle-test)
        echo "[$title] $status · ${duration}초"
        echo "./gradlew test"
        ;;
      test-01-e2e-order-payment-flow)
        echo "[$title] $status · ${duration}초"
        echo "상품 등록 -> 주문 생성 -> 결제 성공 -> 주문 확정"
        echo "최종 주문 상태: $(json_value "$result_file" '.data.finalOrderStatus' '-')"
        ;;
      test-02-stock-oversell-traffic)
        echo "[$title] $status · ${duration}초"
        echo "요청 $(comma_number "$(json_value "$result_file" '.data.requests' '0')")건 · 동시성 $(comma_number "$(json_value "$result_file" '.data.concurrency' '0')") · 초기 재고 $(comma_number "$(json_value "$result_file" '.data.stockLimit' '0')")개"
        echo "주문 성공 $(comma_number "$(json_value "$result_file" '.data.successCount' '0')")건 · 실패 $(comma_number "$(json_value "$result_file" '.data.failureCount' '0')")건 · 초과 판매 $(comma_number "$(json_value "$result_file" '.data.oversellCount' '0')")건"
        ;;
      test-03-order-cancel-lock-traffic)
        echo "[$title] $status · ${duration}초"
        echo "요청 $(comma_number "$(json_value "$result_file" '.data.requests' '0')")건 · 동시성 $(comma_number "$(json_value "$result_file" '.data.concurrency' '0')")"
        echo "취소 성공 $(comma_number "$(json_value "$result_file" '.data.cancelSuccessCount' '0')")건 · 실패 $(comma_number "$(json_value "$result_file" '.data.cancelFailureCount' '0')")건 · 최종 상태 $(json_value "$result_file" '.data.finalOrderStatus' '-')"
        ;;
      test-04-payment-event-idempotency)
        echo "[$title] $status · ${duration}초"
        echo "중복 이벤트 $(comma_number "$(json_value "$result_file" '.data.duplicateEvents' '0')")건"
        echo "처리 완료 $(comma_number "$(json_value "$result_file" '.data.processedEventCount' '0')")건 · 주문 확정 이벤트 $(comma_number "$(json_value "$result_file" '.data.orderConfirmedEventCount' '0')")건 · 최종 상태 $(json_value "$result_file" '.data.finalOrderStatus' '-')"
        ;;
      test-05-outbox-status-transition)
        echo "[$title] $status · ${duration}초"
        echo "$(json_value "$result_file" '.data.initialOutboxStatus' '-') -> $(json_value "$result_file" '.data.finalOutboxStatus' '-') · 재시도 $(comma_number "$(json_value "$result_file" '.data.retryCount' '0')")회"
        ;;
      *)
        echo "[$title] $status · ${duration}초"
        echo "${MESSAGES[$i]}"
        ;;
    esac
    echo
  done
}

run_gradle_verification() {
  local name="gradle-test"
  local start duration status message check_dir check_log
  check_dir="$OUT_DIR/$name"
  check_log="$check_dir/run.log"
  mkdir -p "$check_dir"

  echo
  echo "============================================================"
  echo "[검증] $name"
  echo "명령어: ./gradlew test"
  echo "============================================================"

  start=$(date +%s)
  (
    cd "$ROOT_DIR" || exit 1
    ./gradlew test
  ) > >(tee -a "$check_log") 2>&1
  local code=$?
  duration=$(duration_since "$start")

  if [[ "$code" == "0" ]]; then
    status="PASSED"
    message="Gradle 테스트 태스크가 성공했습니다."
  else
    status="FAILED"
    message="Gradle 테스트 태스크가 실패했습니다. exitCode=$code"
  fi

  record_result "$name" "$status" "$duration" "$message" "" "$check_log"
}

run_scenario_script() {
  local script="$1"
  local name start duration status message result_file log_file scenario_run_id
  name="$(basename "$script" .sh)"
  scenario_run_id="$RUN_ID-$name"

  echo
  echo "============================================================"
  echo "[검증] $name"
  echo "명령어: GATEWAY_URL=$GATEWAY_URL RUN_ID=$scenario_run_id $script"
  echo "============================================================"

  start=$(date +%s)
  GATEWAY_URL="$GATEWAY_URL" RESULT_ROOT="$OUT_DIR/scenarios" RUN_ID="$scenario_run_id" "$script"
  local code=$?
  duration=$(duration_since "$start")

  result_file="$OUT_DIR/scenarios/$scenario_run_id/result.json"
  log_file="$OUT_DIR/scenarios/$scenario_run_id/run.log"

  if [[ -f "$result_file" ]]; then
    status="$(json_value "$result_file" '.status' "FAILED")"
    message="$(json_value "$result_file" '.message' "결과 파일을 읽지 못했습니다.")"
  elif [[ "$code" == "0" ]]; then
    status="PASSED"
    message="스크립트가 성공 종료했지만 result.json을 찾지 못했습니다."
  else
    status="FAILED"
    message="스크립트가 실패했습니다. exitCode=$code"
  fi

  record_result "$name" "$status" "$duration" "$message" "$result_file" "$log_file"
}

write_summary() {
  local end_epoch total_duration passed failed
  end_epoch=$(date +%s)
  total_duration=$((end_epoch - START_EPOCH))
  passed=0
  failed=0

  for status in "${STATUSES[@]}"; do
    if [[ "$status" == "PASSED" ]]; then
      passed=$((passed + 1))
    else
      failed=$((failed + 1))
    fi
  done

  {
    print_capture_summary "$total_duration" "$passed" "$failed"
    echo "개발 및 검증 환경"
    echo "OS: $(uname -srm 2>/dev/null || echo unknown)"
    echo "Java: $(java -version 2>&1 | head -n 1 | sed 's/"/\\"/g' || echo unknown)"
    echo "Gradle: ./gradlew"
    echo "Docker: $(docker --version 2>/dev/null || echo 'not available')"
    echo "Gateway: $GATEWAY_URL"
    echo "Started: $(iso_time "$START_EPOCH")"
    echo "Ended: $(iso_time "$end_epoch")"
    echo
    echo "전체 로그: $LOG_FILE"
  } > "$SUMMARY_FILE"

  if command -v jq >/dev/null 2>&1; then
    local items_json="[]"
    for i in "${!NAMES[@]}"; do
      items_json=$(jq -c \
        --arg name "${NAMES[$i]}" \
        --arg status "${STATUSES[$i]}" \
        --arg message "${MESSAGES[$i]}" \
        --arg resultFile "${RESULT_FILES[$i]}" \
        --arg logFile "${LOG_FILES[$i]}" \
        --argjson durationSeconds "${DURATIONS[$i]}" \
        '. + [{name:$name,status:$status,message:$message,durationSeconds:$durationSeconds,resultFile:$resultFile,logFile:$logFile}]' \
        <<< "$items_json")
    done

    jq -n \
      --arg status "$OVERALL_STATUS" \
      --arg mode "$MODE" \
      --arg gatewayUrl "$GATEWAY_URL" \
      --arg resultDir "$OUT_DIR" \
      --arg summaryFile "$SUMMARY_FILE" \
      --arg logFile "$LOG_FILE" \
      --arg startedAt "$(iso_time "$START_EPOCH")" \
      --arg endedAt "$(iso_time "$end_epoch")" \
      --argjson durationSeconds "$total_duration" \
      --argjson passed "$passed" \
      --argjson failed "$failed" \
      --argjson checks "$items_json" \
      '{status:$status,mode:$mode,gatewayUrl:$gatewayUrl,resultDir:$resultDir,summaryFile:$summaryFile,logFile:$logFile,startedAt:$startedAt,endedAt:$endedAt,durationSeconds:$durationSeconds,passed:$passed,failed:$failed,checks:$checks}' \
      > "$RESULT_FILE"
  fi

  print_capture_summary "$total_duration" "$passed" "$failed"
}

case "$MODE" in
  -h|--help)
    usage
    exit 0
    ;;
  gradle)
    run_gradle_verification
    ;;
  scenario)
    run_scenario_script "$SCRIPT_DIR/test-01-e2e-order-payment-flow.sh"
    run_scenario_script "$SCRIPT_DIR/test-02-stock-oversell-traffic.sh"
    run_scenario_script "$SCRIPT_DIR/test-03-order-cancel-lock-traffic.sh"
    run_scenario_script "$SCRIPT_DIR/test-04-payment-event-idempotency.sh"
    run_scenario_script "$SCRIPT_DIR/test-05-outbox-status-transition.sh"
    ;;
  all)
    run_gradle_verification
    run_scenario_script "$SCRIPT_DIR/test-01-e2e-order-payment-flow.sh"
    run_scenario_script "$SCRIPT_DIR/test-02-stock-oversell-traffic.sh"
    run_scenario_script "$SCRIPT_DIR/test-03-order-cancel-lock-traffic.sh"
    run_scenario_script "$SCRIPT_DIR/test-04-payment-event-idempotency.sh"
    run_scenario_script "$SCRIPT_DIR/test-05-outbox-status-transition.sh"
    ;;
  *)
    usage
    exit 2
    ;;
esac

write_summary

echo
echo "============================================================"
echo "[전체 결과] $OVERALL_STATUS"
echo "[요약 파일] $SUMMARY_FILE"
echo "[결과 파일] $RESULT_FILE"
echo "[로그 파일] $LOG_FILE"
echo "============================================================"

[[ "$OVERALL_STATUS" == "PASSED" ]]
