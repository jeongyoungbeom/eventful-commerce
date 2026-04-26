#!/bin/bash

echo "=========================================="
echo "주문 취소 단일 테스트"
echo "=========================================="

BASE_URL="http://localhost:8081"

# 1. 주문 생성
echo ""
echo "1단계: 주문 생성..."
ORDER_RESPONSE=$(curl -s -X POST $BASE_URL/orders \
  -H "Content-Type: application/json" \
  -d '[
    {
      "userId": "9c8f457f-d0de-4407-9f1d-29b014e9bdb7",
      "productId": "PRODUCT-001",
      "quantity": 1,
      "totalAmount": 10000
    }
  ]')

echo "응답: $ORDER_RESPONSE"

# 주문 ID 추출 (배열에서 첫 번째 요소)
ORDER_ID=$(echo $ORDER_RESPONSE | jq -r '.[0]')

if [ -z "$ORDER_ID" ] || [ "$ORDER_ID" == "null" ]; then
    echo "주문 생성 실패!"
    exit 1
fi

echo "✅ 주문 생성 성공: $ORDER_ID"

# 2. 잠시 대기
echo ""
echo "⏳ 2초 대기..."
sleep 2

# 3. 주문 취소
echo ""
echo "2단계: 주문 취소..."
CANCEL_RESPONSE=$(curl -s -X POST $BASE_URL/orders/$ORDER_ID/cancel)

echo "응답: $CANCEL_RESPONSE"

# 성공 여부 확인
SUCCESS=$(echo $CANCEL_RESPONSE | jq -r '.success')

if [ "$SUCCESS" == "true" ]; then
    echo "주문 취소 성공!"
else
    echo "주문 취소 실패!"
    exit 1
fi

# 4. 재고 확인
echo ""
echo "📊 3단계: 재고 확인..."
STOCK=$(docker exec redis-node-1 redis-cli -c -p 7001 get "{product:PRODUCT-001}:stock")
echo "현재 재고: $STOCK"

echo ""
echo "=========================================="
echo "테스트 완료!"
echo "=========================================="
