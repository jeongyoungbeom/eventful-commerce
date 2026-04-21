#!/bin/bash

echo "🚀 Starting development infrastructure..."
echo ""

# 기존 컨테이너 정리
echo "🧹 Cleaning up existing containers..."
docker-compose -f docker-compose-dev.yml down 2>/dev/null

# 컨테이너 시작
echo "📦 Starting containers..."
docker-compose -f docker-compose-dev.yml up -d

# 서비스 준비 대기
echo "⏳ Waiting for services to be ready..."
sleep 10

# Redis Cluster 초기화 확인
echo "🔧 Initializing Redis Cluster..."

# -it 제거 (스크립트 실행 시 문제 발생)
docker exec redis-node-1 redis-cli --cluster create \
  redis-node-1:7001 \
  redis-node-2:7002 \
  redis-node-3:7003 \
  redis-node-4:7004 \
  redis-node-5:7005 \
  redis-node-6:7006 \
  --cluster-replicas 1 \
  --cluster-yes

if [ $? -eq 0 ]; then
  echo "✅ Redis Cluster initialized successfully"
else
  echo "⚠️  Redis Cluster initialization failed - trying manual setup..."
  echo ""
  echo "Please run manually:"
  echo "docker exec -it redis-node-1 redis-cli --cluster create \\"
  echo "  redis-node-1:7001 redis-node-2:7002 redis-node-3:7003 \\"
  echo "  redis-node-4:7004 redis-node-5:7005 redis-node-6:7006 \\"
  echo "  --cluster-replicas 1 --cluster-yes"
fi

echo ""
echo "✅ All services started!"
echo ""
echo "📊 Service URLs:"
echo "  PostgreSQL:    localhost:5432 (user: postgres, password: postgres)"
echo "  Redis Cluster: localhost:7001-7006"
echo "  Kafka:         localhost:9092"
echo ""
echo "🔍 Check status: docker-compose -f docker-compose-dev.yml ps"
echo "📋 View logs:    docker-compose -f docker-compose-dev.yml logs -f [service-name]"
echo "🛑 Stop all:     docker-compose -f docker-compose-dev.yml down"
echo ""
