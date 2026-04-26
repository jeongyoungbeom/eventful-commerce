#!/bin/bash

# Redis Cluster 초기 재고 설정
echo "🚀 Initializing Redis Cluster stock for multiple products..."

# 상품 목록
products=("PRODUCT-001" "PRODUCT-002" "PRODUCT-003")

for productId in "${products[@]}"; do
  echo "📦 Setting stock for $productId..."

  # -c 옵션: 클러스터 모드로 연결 (자동 리다이렉션)
  docker exec -it redis-node-1 redis-cli -c -p 7001 SET "{product:$productId}:stock" 100
  docker exec -it redis-node-1 redis-cli -c -p 7001 SET "{product:$productId}:holdCount" 0

  echo "✅ $productId initialized with stock=100"
done

echo ""
echo "✅ Redis Cluster initialized successfully!"
echo "Products: ${products[@]}"
