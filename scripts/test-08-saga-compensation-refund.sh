#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/test-helpers.sh
source "$SCRIPT_DIR/lib/test-helpers.sh"

init_test "saga-compensation-refund"

cleanup_test_data() {
  [[ "$CLEANUP_DONE" == "1" ]] && return 0
  CLEANUP_DONE=1
  [[ "${KEEP_TEST_DATA:-0}" == "1" ]] && return 0
  echo "[정리] Saga 보상 테스트 데이터를 삭제합니다"
  if [[ -n "${SHIPPING_FAILURE_EVENT_ID:-}" ]]; then
    psql_exec order_service "delete from processed_event where event_id='$SHIPPING_FAILURE_EVENT_ID';"
    psql_exec notification_service "delete from processed_event where event_id='$SHIPPING_FAILURE_EVENT_ID';"
  fi
  cleanup_common_order_data
}

require_cmd curl
require_cmd jq
require_cmd docker

TS=$(date +%s)
INITIAL_STOCK="${INITIAL_STOCK:-10}"
ORDER_QUANTITY="${ORDER_QUANTITY:-2}"
DUPLICATE_FAILURE_EVENTS="${DUPLICATE_FAILURE_EVENTS:-20}"
PAYMENT_AMOUNT=$((12000 * ORDER_QUANTITY))
EXPECTED_SOLD_STOCK=$((INITIAL_STOCK - ORDER_QUANTITY))

echo "============================================================"
echo "배송 실패 → 주문 취소 → 재고 재입고 → 결제 환불 Saga 테스트"
echo "중복 SHIPPING_FAILED 이벤트=$DUPLICATE_FAILURE_EVENTS"
echo "결과 경로: $OUT_DIR"
echo "============================================================"

echo "[단계] 판매자/사용자/상품/주문 생성"
create_test_principals "saga" "$TS"
create_test_product "$TS" "$INITIAL_STOCK" 12000
STOCK_KEY="{product:$PRODUCT_ID}:stock"
wait_for_db_value order_service "select count(*) from product_read_model where product_id='$PRODUCT_ID';" "1" 60 \
  || fail_test "상품 읽기 모델이 생성되지 않았습니다"

ORDER_KEY="test-08-$TS-$(uuid_value)"
ORDER_PAYLOAD="{\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":$ORDER_QUANTITY}]}"
http_json POST "$GATEWAY_URL/api/orders" "$ORDER_PAYLOAD" "$USER_TOKEN" "$USER_ID" "USER" "$ORDER_KEY"
[[ "$RESPONSE_STATUS" == "200" ]] || fail_test "주문 생성 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
ORDER_ID=$(jq -r '.orderId' <<< "$RESPONSE_BODY")
SELLER_ORDER_ID=$(jq -r '.sellerOrders[0].sellerOrderId' <<< "$RESPONSE_BODY")
[[ -n "$ORDER_ID" && "$ORDER_ID" != "null" && -n "$SELLER_ORDER_ID" && "$SELLER_ORDER_ID" != "null" ]] \
  || fail_test "주문/판매자 주문 ID를 찾지 못했습니다: $RESPONSE_BODY"

echo "[단계] 결제 예약 후 결제 완료 및 주문 확정"
wait_for_db_value payment_service "select count(*) from payment where order_id='$ORDER_ID';" "1" 60 \
  || fail_test "결제 예약이 생성되지 않았습니다"
http_json POST "$GATEWAY_URL/api/payments/webhook" \
  "{\"orderId\":\"$ORDER_ID\",\"result\":\"SUCCESS\",\"pgTxId\":\"PG-SAGA-$TS\",\"amount\":$PAYMENT_AMOUNT}"
[[ "$RESPONSE_STATUS" == "200" ]] || fail_test "결제 웹훅 실패: HTTP $RESPONSE_STATUS $RESPONSE_BODY"
wait_for_order_status "$ORDER_ID" "ORDER_CONFIRMED" 60 || fail_test "주문이 ORDER_CONFIRMED가 되지 않았습니다"
wait_for_redis_value "$STOCK_KEY" "$EXPECTED_SOLD_STOCK" 30 || fail_test "확정 주문의 Redis 재고가 기대값과 다릅니다"
wait_for_db_value product_service "select stock from products where id='$PRODUCT_ID';" "$EXPECTED_SOLD_STOCK" 60 \
  || fail_test "확정 주문의 Product DB 재고가 기대값과 다릅니다"

echo "[단계] 동일 SHIPPING_FAILED 이벤트를 여러 번 발행해 보상 흐름 시작"
SHIPPING_FAILURE_EVENT_ID=$(uuid_value)
FAILED_AT=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
SHIPPING_FAILURE_PAYLOAD=$(jq -cn \
  --arg orderId "$ORDER_ID" \
  --arg sellerOrderId "$SELLER_ORDER_ID" \
  --arg userId "$USER_ID" \
  --arg failedAt "$FAILED_AT" \
  '{orderId:$orderId,sellerOrderId:$sellerOrderId,userId:$userId,reason:"TEST_SHIPPING_FAILURE",failedAt:$failedAt}')
SHIPPING_FAILURE_MESSAGE=$(jq -cn \
  --arg eventId "$SHIPPING_FAILURE_EVENT_ID" \
  --arg aggregateId "$SELLER_ORDER_ID" \
  --arg occurredAt "$FAILED_AT" \
  --arg payload "$SHIPPING_FAILURE_PAYLOAD" \
  '{eventId:$eventId,aggregateType:"SHIPPING",aggregateId:$aggregateId,eventType:"SHIPPING_FAILED",occurredAt:$occurredAt,payload:$payload}')

for _ in $(seq 1 "$DUPLICATE_FAILURE_EVENTS"); do
  echo "$SHIPPING_FAILURE_MESSAGE"
done | docker exec -i eventful-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic shipping-events >/dev/null

echo "[단계] 주문/결제/재고/Saga가 보상 완료 상태에 도달할 때까지 대기"
wait_for_db_value order_service "select status from order_saga where order_id='$ORDER_ID';" "COMPENSATED" 90 \
  || fail_test "Saga가 COMPENSATED 상태에 도달하지 못했습니다"
wait_for_db_value payment_service "select status from payment where order_id='$ORDER_ID';" "PAYMENT_REFUNDED" 60 \
  || fail_test "결제가 PAYMENT_REFUNDED 상태에 도달하지 못했습니다"
wait_for_redis_value "$STOCK_KEY" "$INITIAL_STOCK" 60 || fail_test "보상 후 Redis 재고가 재입고되지 않았습니다"
wait_for_db_value product_service "select stock from products where id='$PRODUCT_ID';" "$INITIAL_STOCK" 60 \
  || fail_test "보상 후 Product DB 재고가 재입고되지 않았습니다"

FINAL_ORDER_STATUS=$(psql_value order_service "select status from orders where id='$ORDER_ID';")
FINAL_SELLER_ORDER_STATUS=$(psql_value order_service "select status from seller_orders where id='$SELLER_ORDER_ID';")
SAGA_ROW=$(psql_value order_service "select status || '|' || expected_refund_count || '|' || refunded_count from order_saga where order_id='$ORDER_ID';")
IFS='|' read -r SAGA_STATUS EXPECTED_REFUNDS REFUNDED_COUNT <<< "$SAGA_ROW"
REFUND_COUNT=$(psql_value payment_service "select count(*) from payment_refund where order_id='$ORDER_ID' and seller_order_id='$SELLER_ORDER_ID';")
RECEIPT_COUNT=$(psql_value order_service "select count(*) from order_saga_refund_receipt where seller_order_id='$SELLER_ORDER_ID';")
PROCESSED_COUNT=$(psql_value order_service "select count(*) from processed_event where event_id='$SHIPPING_FAILURE_EVENT_ID';")
RESTOCK_EVENT_COUNT=$(psql_value order_service "select count(*) from outbox_event where event_type='INVENTORY_STOCK_ADJUSTED' and aggregate_id='$PRODUCT_ID' and payload like '%$ORDER_ID%' and payload like '%\"reason\":\"RESTOCKED\"%';")

[[ "$FINAL_ORDER_STATUS" == "ORDER_CANCELED" ]] || fail_test "최종 주문 상태가 취소가 아닙니다: $FINAL_ORDER_STATUS"
[[ "$FINAL_SELLER_ORDER_STATUS" == "CANCELED" ]] || fail_test "판매자 주문 상태가 취소가 아닙니다: $FINAL_SELLER_ORDER_STATUS"
[[ "$SAGA_STATUS" == "COMPENSATED" && "$EXPECTED_REFUNDS" == "1" && "$REFUNDED_COUNT" == "1" ]] \
  || fail_test "Saga 환불 카운트가 올바르지 않습니다: $SAGA_ROW"
[[ "$REFUND_COUNT" == "1" && "$RECEIPT_COUNT" == "1" ]] \
  || fail_test "중복 이벤트로 환불/수신 기록이 중복 생성됐습니다: refund=$REFUND_COUNT receipt=$RECEIPT_COUNT"
[[ "$PROCESSED_COUNT" == "1" ]] || fail_test "SHIPPING_FAILED processed_event가 1건이 아닙니다: $PROCESSED_COUNT"
[[ "$RESTOCK_EVENT_COUNT" == "1" ]] || fail_test "재입고 원장 이벤트가 1건이 아닙니다: $RESTOCK_EVENT_COUNT"

RESULT_DATA_JSON=$(jq -n \
  --arg productId "$PRODUCT_ID" \
  --arg orderId "$ORDER_ID" \
  --arg sellerOrderId "$SELLER_ORDER_ID" \
  --arg shippingFailureEventId "$SHIPPING_FAILURE_EVENT_ID" \
  --arg sagaStatus "$SAGA_STATUS" \
  --arg paymentStatus "PAYMENT_REFUNDED" \
  --argjson duplicateEvents "$DUPLICATE_FAILURE_EVENTS" \
  --argjson refundCount "$REFUND_COUNT" \
  --argjson receiptCount "$RECEIPT_COUNT" \
  '{productId:$productId,orderId:$orderId,sellerOrderId:$sellerOrderId,shippingFailureEventId:$shippingFailureEventId,sagaStatus:$sagaStatus,paymentStatus:$paymentStatus,duplicateEvents:$duplicateEvents,refundCount:$refundCount,receiptCount:$receiptCount}')
pass_test "중복 배송 실패 이벤트에도 취소·재입고·환불이 각 1회 처리되고 Saga가 COMPENSATED로 완료됨을 검증했습니다."
