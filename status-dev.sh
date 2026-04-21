#!/bin/bash

echo "📊 Development Infrastructure Status"
echo "===================================="
echo ""

# 컨테이너 상태
echo "🐳 Container Status:"
docker-compose -f docker-compose-dev.yml ps

echo ""
echo "🔍 Quick Checks:"
echo ""

# PostgreSQL 체크
echo -n "  PostgreSQL: "
docker exec eventful-postgres pg_isready -U postgres > /dev/null 2>&1
if [ $? -eq 0 ]; then
  echo "✅ Running"
else
  echo "❌ Not running"
fi

# Redis Cluster 체크
echo -n "  Redis Cluster: "
REDIS_CHECK=$(docker exec redis-node-1 redis-cli -p 7001 cluster info 2>/dev/null | grep cluster_state:ok)
if [ ! -z "$REDIS_CHECK" ]; then
  echo "✅ Running ($(docker exec redis-node-1 redis-cli -p 7001 cluster nodes 2>/dev/null | grep -c master) masters)"
else
  echo "❌ Not running or not initialized"
fi

# Kafka 체크
echo -n "  Kafka: "
docker exec eventful-kafka kafka-broker-api-versions.sh --bootstrap-server localhost:9092 > /dev/null 2>&1
if [ $? -eq 0 ]; then
  echo "✅ Running"
else
  echo "❌ Not running"
fi

echo ""
echo "📋 Useful commands:"
echo "  View logs:    docker-compose -f docker-compose-dev.yml logs -f [service-name]"
echo "  Restart:      docker-compose -f docker-compose-dev.yml restart [service-name]"
echo "  Stop all:     ./stop-dev.sh"
echo ""
