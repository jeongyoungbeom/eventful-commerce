#!/bin/bash

echo "⏳ Waiting for Redis nodes to be ready..."
sleep 10

# 이미 클러스터가 구성되어 있는지 확인
CLUSTER_STATE=$(docker exec redis-node-1 redis-cli -p 7001 cluster info 2>/dev/null | grep "cluster_state" | tr -d '\r')

if [ "$CLUSTER_STATE" = "cluster_state:ok" ]; then
  echo "✅ Redis Cluster already exists, skipping creation."
  echo ""
  echo "📊 Cluster Status:"
  docker exec redis-node-1 redis-cli -p 7001 cluster nodes
  exit 0
fi

echo "🔧 Creating Redis Cluster..."
docker exec -i redis-node-1 redis-cli --cluster create \
  redis-node-1:7001 \
  redis-node-2:7002 \
  redis-node-3:7003 \
  redis-node-4:7004 \
  redis-node-5:7005 \
  redis-node-6:7006 \
  --cluster-replicas 1 \
  --cluster-yes

if [ $? -eq 0 ]; then
  echo "✅ Redis Cluster created successfully!"
  echo ""
  echo "📊 Cluster Status:"
  docker exec redis-node-1 redis-cli -p 7001 cluster nodes
else
  echo "❌ Failed to create Redis Cluster"
  exit 1
fi
