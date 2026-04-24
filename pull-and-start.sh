#!/bin/bash

# Docker Hub username
DOCKER_USERNAME="jybeomss1"

echo "🐳 Pulling images from Docker Hub and starting services"
echo "=========================================="
echo "Username: ${DOCKER_USERNAME}"
echo ""

# Pull latest images
echo "📥 Pulling latest images..."
echo ""

echo "⬇️  Pulling order-service..."
docker pull ${DOCKER_USERNAME}/order-service:latest

echo "⬇️  Pulling payment-service..."
docker pull ${DOCKER_USERNAME}/payment-service:latest

echo "⬇️  Pulling shipping-service..."
docker pull ${DOCKER_USERNAME}/shipping-service:latest

echo ""
echo "✅ All images pulled successfully!"
echo ""

# Make init script executable
chmod +x scripts/init-redis-cluster.sh

# Start all services
echo "🚀 Starting all containers..."
docker-compose up -d

echo ""
echo "⏳ Waiting for infrastructure to be ready..."
sleep 15

# Initialize Redis Cluster
echo ""
echo "🔧 Initializing Redis Cluster..."
./scripts/init-redis-cluster.sh

echo ""
echo "=========================================="
echo "✅ All services started!"
echo ""
echo "📊 Service URLs:"
echo "  - Order Service:    http://localhost:8081"
echo "  - Payment Service:  http://localhost:8082"
echo "  - Shipping Service: http://localhost:8083"
echo "  - PostgreSQL:       localhost:5432"
echo "  - Redis Cluster:    localhost:7001-7006"
echo "  - Kafka:            localhost:9092"
echo ""
echo "📝 Check status: docker-compose ps"
echo "📋 View logs:    docker-compose logs -f [service-name]"
echo ""
