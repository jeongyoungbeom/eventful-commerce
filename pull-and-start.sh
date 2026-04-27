#!/bin/bash

# Docker Hub username
DOCKER_USERNAME="jybeomss1"

echo "🐳 Pulling images from Docker Hub and starting services"
echo "=========================================="
echo "Username: ${DOCKER_USERNAME}"
echo ""

prompt_with_default() {
    local prompt="$1"
    local default_value="$2"
    local input

    if [ -n "$default_value" ]; then
        read -r -p "${prompt} [${default_value}]: " input
        echo "${input:-$default_value}"
    else
        read -r -p "${prompt}: " input
        echo "$input"
    fi
}

echo "📨 Telegram notification settings"
echo "Leave blank if you do not want Telegram notifications."
echo ""

TELEGRAM_BOT_TOKEN=$(prompt_with_default "Telegram bot token" "${TELEGRAM_BOT_TOKEN:-}")
TELEGRAM_BOT_USERNAME=$(prompt_with_default "Telegram bot username" "${TELEGRAM_BOT_USERNAME:-eventful_commerce_bot}")
TELEGRAM_DEFAULT_USER_ID=$(prompt_with_default "Default userId to receive notifications" "${TELEGRAM_DEFAULT_USER_ID:-297a520a-f08d-4ca9-8ec9-5bfe21c0575a}")
TELEGRAM_DEFAULT_CHAT_ID=$(prompt_with_default "Telegram chatId for that userId" "${TELEGRAM_DEFAULT_CHAT_ID:-}")

export TELEGRAM_BOT_TOKEN
export TELEGRAM_BOT_USERNAME
export TELEGRAM_DEFAULT_USER_ID
export TELEGRAM_DEFAULT_CHAT_ID

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

echo "⬇️  Pulling notification-service..."
docker pull ${DOCKER_USERNAME}/notification-service:latest

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
echo "  - Notification:     http://localhost:8084"
echo "  - PostgreSQL:       localhost:5432"
echo "  - Redis Cluster:    localhost:7001-7006"
echo "  - Kafka:            localhost:9092"
echo ""
echo "📝 Check status: docker-compose ps"
echo "📋 View logs:    docker-compose logs -f [service-name]"
echo ""
