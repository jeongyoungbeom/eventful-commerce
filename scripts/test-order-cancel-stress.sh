#!/bin/bash

echo "=========================================="
echo "주문 취소 스트레스 테스트 (10개 동시)"
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

# 3. 10개 동시 취소 요청
echo ""
echo "2단계: 10개 동시 취소 요청..."

declare -a PIDS
declare -a TEMP_FILES

for i in {1..10}; do
    TEMP_FILE=$(mktemp)
    TEMP_FILES[$i]=$TEMP_FILE
    (curl -s -X POST $BASE_URL/orders/$ORDER_ID/cancel > $TEMP_FILE) &
    PIDS[$i]=$!
done

# 모든 요청 완료 대기
for pid in ${PIDS[@]}; do
    wait $pid
done

# 결과 집계
SUCCESS_COUNT=0
for i in {1..10}; do
    RESPONSE=$(cat ${TEMP_FILES[$i]})
    SUCCESS=$(echo $RESPONSE | jq -r '.success')
    if [ "$SUCCESS" == "true" ]; then
        SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
        echo "요청 $i: 성공"
    else
        echo "요청 $i: 실패"
    fi
done

echo ""
echo "=========================================="
echo "성공 횟수: $SUCCESS_COUNT / 10"
echo "=========================================="

# 정확히 한 번만 성공해야 함
if [ $SUCCESS_COUNT -eq 1 ]; then
    echo "스트레스 테스트 통과! 정확히 한 번만 성공했습니다."
else
    echo "스트레스 테스트 실패! $SUCCESS_COUNT 번 성공했습니다. (예상: 1번)"
    for file in ${TEMP_FILES[@]}; do
        rm $file
    done
    exit 1
fi

# 임시 파일 삭제
for file in ${TEMP_FILES[@]}; do
    rm $file
done

echo ""
echo "=========================================="
echo "스트레스 테스트 완료!"
echo "=========================================="