#!/bin/bash

# Redis 초기 재고 설정
echo "Initializing Redis stock..."

docker exec -it eventful-redis redis-cli SET stock:default 100
docker exec -it eventful-redis redis-cli SET holdCount:default 0

echo "✅ Redis initialized with stock:default = 100"
