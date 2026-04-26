#!/bin/bash

echo "=========================================="
echo "주문 취소 동시성 테스트 (중복 클릭)"
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

ORDER_ID=$(echo $ORDER_RESPONSE | jq -r '.[0]')

if [ -z "$ORDER_ID" ] || [ "$ORDER_ID" == "null" ]; then
    echo "주문 생성 실패!"
    exit 1
fi

echo "주문 생성 성공: $ORDER_ID"

# 2. 잠시 대기
echo ""
echo "2초 대기..."
sleep 2

# 3. 동시 취소 요청
echo ""
echo "2단계: 동시 취소 요청 (2개)..."

# 임시 파일 생성
TEMP_FILE1=$(mktemp)
TEMP_FILE2=$(mktemp)

# 백그라운드에서 동시 실행
(curl -s -X POST $BASE_URL/orders/$ORDER_ID/cancel > $TEMP_FILE1) &
PID1=$!

(curl -s -X POST $BASE_URL/orders/$ORDER_ID/cancel > $TEMP_FILE2) &
PID2=$!

# 두 요청 완료 대기
wait $PID1
wait $PID2

RESPONSE1=$(cat $TEMP_FILE1)
RESPONSE2=$(cat $TEMP_FILE2)

echo ""
echo "첫 번째 응답: $RESPONSE1"
echo "두 번째 응답: $RESPONSE2"

# 성공/실패 카운트
SUCCESS1=$(echo $RESPONSE1 | jq -r '.success')
SUCCESS2=$(echo $RESPONSE2 | jq -r '.success')

SUCCESS_COUNT=0
if [ "$SUCCESS1" == "true" ]; then
    SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
fi
if [ "$SUCCESS2" == "true" ]; then
    SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
fi

echo ""
echo "=========================================="
echo "성공 횟수: $SUCCESS_COUNT / 2"
echo "=========================================="

# 정확히 한 번만 성공해야 함
if [ $SUCCESS_COUNT -eq 1 ]; then
    echo "테스트 통과! 정확히 한 번만 성공했습니다."
else
    echo "테스트 실패! $SUCCESS_COUNT 번 성공했습니다. (예상: 1번)"
    rm $TEMP_FILE1 $TEMP_FILE2
    exit 1
fi

# 임시 파일 삭제
rm $TEMP_FILE1 $TEMP_FILE2

# 4. 재고 확인
echo ""
echo "3단계: 재고 확인..."
STOCK=$(docker exec redis-node-1 redis-cli -c -p 7001 get "{product:PRODUCT-001}:stock")
echo "현재 재고: $STOCK (100개로 복구되어야 함)"

echo ""
echo "=========================================="
echo "동시성 테스트 완료!"
echo "=========================================="