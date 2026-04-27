#!/bin/bash

set -e

echo "=========================================="
echo "알림 전체 플로우 테스트"
echo "주문 접수 -> 결제 완료 -> 배송 시작 -> 배송 완료"
echo "=========================================="

# 서비스 URL
ORDER_URL="${ORDER_URL:-http://localhost:8081}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8082}"
NOTIFICATION_URL="${NOTIFICATION_URL:-http://localhost:8084}"

# PostgreSQL 접속 정보
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-eventful-postgres}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"
NOTIFICATION_DB="notification_service"

# 테스트 데이터
USER_ID="${TEST_USER_ID:-297a520a-f08d-4ca9-8ec9-5bfe21c0575a}"
PRODUCT_ID="${PRODUCT_ID:-PRODUCT-001}"
TOTAL_AMOUNT="${TOTAL_AMOUNT:-10000}"

# PostgreSQL 쿼리 실행 헬퍼
psql_query() {
    local db_name="$1"
    local sql="$2"

    docker exec -e PGPASSWORD="$DB_PASSWORD" "$POSTGRES_CONTAINER" \
        psql -U "$DB_USER" -d "$db_name" -tAc "$sql" 2>/dev/null || echo ""
}

# 알림 카운트 조회
notification_count() {
    local type="$1"
    local count
    count=$(psql_query "$NOTIFICATION_DB" \
        "SELECT COUNT(*) FROM notifications WHERE order_id = '$ORDER_ID' AND type = '$type';")
    echo "${count:-0}" | tr -d '[:space:]'
}

# 알림 대기
wait_for_notification() {
    local type="$1"
    local timeout_seconds="$2"
    local elapsed=0

    echo -n "⏳ $type 알림 대기 중"

    while [ "$elapsed" -lt "$timeout_seconds" ]; do
        local count
        count=$(notification_count "$type")

        if [ "${count:-0}" -gt 0 ]; then
            echo " ✅ (${elapsed}s)"
            return 0
        fi

        echo -n "."
        sleep 1
        elapsed=$((elapsed + 1))
    done

    echo " ❌ (타임아웃: ${timeout_seconds}s)"
    return 1
}

# Payment 생성 대기
wait_for_payment_created() {
    local timeout_seconds="$1"
    local elapsed=0

    echo -n "⏳ Payment 생성 대기 중"

    while [ "$elapsed" -lt "$timeout_seconds" ]; do
        local status
        status=$(psql_query payment_service \
            "SELECT status FROM payment WHERE order_id = '$ORDER_ID' LIMIT 1;")

        if [ -n "$status" ]; then
            echo " ✅ (status=$status, ${elapsed}s)"
            return 0
        fi

        echo -n "."
        sleep 1
        elapsed=$((elapsed + 1))
    done

    echo " ❌ (타임아웃: ${timeout_seconds}s)"
    return 1
}

echo ""
echo "🔧 테스트 설정:"
echo "  User ID:    $USER_ID"
echo "  Product ID: $PRODUCT_ID"
echo "  Amount:     $TOTAL_AMOUNT"
echo ""

# 텔레그램 사용자 등록 확인
CHAT_ID=$(psql_query "$NOTIFICATION_DB" \
    "SELECT chat_id FROM user_chat_ids WHERE user_id = '$USER_ID' LIMIT 1;")

if [ -z "$CHAT_ID" ]; then
    echo "⚠️  텔레그램 사용자가 등록되지 않았습니다!"
    echo "   다음 명령어를 먼저 실행하세요:"
    echo "   ./scripts/init-telegram-users.sh"
    echo ""
    exit 1
fi

echo "✅ 텔레그램 사용자 확인: chatId=$CHAT_ID"
echo ""

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "1단계: 주문 생성 (ORDER_RESERVED 알림 발생)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

ORDER_PAYLOAD=$(jq -n \
  --arg userId "$USER_ID" \
  --arg productId "$PRODUCT_ID" \
  --argjson totalAmount "$TOTAL_AMOUNT" \
  '[{
    userId: $userId,
    productId: $productId,
    totalAmount: $totalAmount
  }]')

echo "📤 주문 요청..."
ORDER_RESPONSE=$(curl -s -X POST "$ORDER_URL/orders" \
  -H "Content-Type: application/json" \
  -d "$ORDER_PAYLOAD")

ORDER_ID=$(echo "$ORDER_RESPONSE" | jq -r '.[0]' 2>/dev/null)

if [ -z "$ORDER_ID" ] || [ "$ORDER_ID" = "null" ]; then
    echo "❌ 주문 생성 실패!"
    echo "응답: $ORDER_RESPONSE"
    exit 1
fi

echo "✅ 주문 생성 완료: $ORDER_ID"
echo ""

wait_for_notification "ORDER_RESERVED" 20 || {
    echo "⚠️  ORDER_RESERVED 알림 실패!"
    echo "💡 Kafka Consumer 로그 확인: docker logs notification-service --tail 50"
}

wait_for_payment_created 20 || {
    echo "⚠️  Payment 생성 실패!"
    exit 1
}

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "2단계: 결제 완료 (PAYMENT_COMPLETED 알림 발생)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

PAYMENT_PAYLOAD=$(jq -n \
  --arg orderId "$ORDER_ID" \
  --arg result "SUCCESS" \
  --arg pgTxId "PG-TX-$(date +%s)" \
  --argjson amount "$TOTAL_AMOUNT" \
  '{
    orderId: $orderId,
    result: $result,
    pgTxId: $pgTxId,
    amount: $amount
  }')

echo "📤 결제 Webhook 요청..."
PAYMENT_RESPONSE=$(curl -s -X POST "$PAYMENT_URL/payments/webhook" \
  -H "Content-Type: application/json" \
  -d "$PAYMENT_PAYLOAD")

echo "✅ 결제 Webhook 완료"
echo ""

wait_for_notification "PAYMENT_COMPLETED" 20 || {
    echo "⚠️  PAYMENT_COMPLETED 알림 실패!"
}

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "3단계: 배송 시작/완료 (자동 처리, 약 10초 소요)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

wait_for_notification "SHIPPING_STARTED" 30 || {
    echo "⚠️  SHIPPING_STARTED 알림 실패!"
}

wait_for_notification "SHIPPING_COMPLETED" 40 || {
    echo "⚠️  SHIPPING_COMPLETED 알림 실패!"
}

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "결과 확인"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""
echo "📊 생성된 알림 목록:"
docker exec -e PGPASSWORD="$DB_PASSWORD" "$POSTGRES_CONTAINER" \
    psql -U "$DB_USER" -d "$NOTIFICATION_DB" -c \
    "SELECT 
        type, 
        title,
        sent_to_telegram as sent,
        is_read as read,
        TO_CHAR(created_at, 'HH24:MI:SS') as time
     FROM notifications
     WHERE order_id = '$ORDER_ID'
     ORDER BY created_at;"

echo ""
echo "📈 알림 통계:"
TOTAL_COUNT=$(psql_query "$NOTIFICATION_DB" \
    "SELECT COUNT(*) FROM notifications WHERE order_id = '$ORDER_ID';")
SENT_COUNT=$(psql_query "$NOTIFICATION_DB" \
    "SELECT COUNT(*) FROM notifications WHERE order_id = '$ORDER_ID' AND sent_to_telegram = true;")

echo "  전체: ${TOTAL_COUNT:-0}개"
echo "  전송: ${SENT_COUNT:-0}개"

if [ "${TOTAL_COUNT:-0}" -eq 5 ] && [ "${SENT_COUNT:-0}" -eq 5 ]; then
    echo "  ✅ 모든 알림 전송 성공!"
else
    echo "  ⚠️  일부 알림이 전송되지 않았습니다."
fi

echo ""
echo "🔍 notification-service 최근 로그 (마지막 30줄):"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
docker logs notification-service --tail 30 2>&1 | tail -30

echo ""
echo "=========================================="
echo "✅ 알림 전체 플로우 테스트 완료"
echo "=========================================="
echo ""
echo "📋 주문번호: $ORDER_ID"
echo ""
echo "💡 추가 확인사항:"
echo "   1. 텔레그램 봇에서 메시지 확인"
echo "   2. Notification API 확인:"
echo "      curl http://localhost:8084/notifications/$USER_ID"
echo ""
