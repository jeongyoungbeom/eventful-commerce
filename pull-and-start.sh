#!/bin/bash

DOCKER_USERNAME="jybeomss1"

echo "🐳 Eventful Commerce - Production Deployment"
echo "=========================================="
echo ""

# Check if .env file exists
if [ ! -f .env ]; then
    echo "📝 .env file not found. Creating with auto-generated secrets..."
    
    # Generate JWT secret
    if command -v openssl &> /dev/null; then
        JWT_SECRET=$(openssl rand -base64 64 | tr -d '\n')
        
        # Create .env file
        cat > .env << EOF
# Auto-generated environment variables
# Generated at: $(date)

# JWT Secret (Auto-generated)
JWT_SECRET=${JWT_SECRET}

# Telegram Notification (Optional - configure via API)
TELEGRAM_BOT_TOKEN=
TELEGRAM_BOT_USERNAME=
TELEGRAM_DEFAULT_USER_ID=
TELEGRAM_DEFAULT_CHAT_ID=
EOF
        
        echo "✅ .env file created with secure JWT secret"
        echo ""
    else
        echo "❌ Error: openssl not found!"
        echo "Please install openssl: sudo apt-get install openssl"
        exit 1
    fi
else
    # Check if JWT_SECRET exists in .env
    if ! grep -q "JWT_SECRET=" .env || ! grep -q "JWT_SECRET=.\+" .env; then
        echo "⚠️  JWT_SECRET not found in .env file"
        echo "📝 Generating and adding JWT_SECRET..."
        
        if command -v openssl &> /dev/null; then
            JWT_SECRET=$(openssl rand -base64 64 | tr -d '\n')
            
            # Add or update JWT_SECRET in .env
            if grep -q "JWT_SECRET=" .env; then
                sed -i "s|JWT_SECRET=.*|JWT_SECRET=${JWT_SECRET}|" .env
            else
                echo "" >> .env
                echo "# Auto-generated JWT Secret" >> .env
                echo "JWT_SECRET=${JWT_SECRET}" >> .env
            fi
            
            echo "✅ JWT_SECRET added to .env file"
            echo ""
        else
            echo "❌ Error: openssl not found!"
            exit 1
        fi
    else
        echo "✅ .env file found with JWT_SECRET"
        echo ""
    fi
fi

# Pull latest images
echo "📥 Pulling latest images from Docker Hub..."
echo "Username: ${DOCKER_USERNAME}"
echo ""

docker pull ${DOCKER_USERNAME}/api-gateway:latest
docker pull ${DOCKER_USERNAME}/order-service:latest
docker pull ${DOCKER_USERNAME}/payment-service:latest
docker pull ${DOCKER_USERNAME}/shipping-service:latest
docker pull ${DOCKER_USERNAME}/notification-service:latest
docker pull ${DOCKER_USERNAME}/user-service:latest
docker pull ${DOCKER_USERNAME}/product-service:latest
docker pull ${DOCKER_USERNAME}/settlement-service:latest

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
echo "✅ All services started successfully!"
echo ""
echo "📊 Service URLs:"
echo "  - Order Service:      http://localhost:8081"
echo "  - Payment Service:    http://localhost:8082"
echo "  - Shipping Service:   http://localhost:8083"
echo "  - Notification:       http://localhost:8084"
echo "  - User Service:       http://localhost:8085"
echo "  - Product Service:    http://localhost:8086"
echo "  - Settlement Service: http://localhost:8087"
echo "  - PostgreSQL:       localhost:5432"
echo "  - Redis Cluster:    localhost:7001-7006"
echo "  - Kafka:            localhost:9092"
echo ""
echo "📝 Useful Commands:"
echo "  - Check status: docker-compose ps"
echo "  - View logs:    docker-compose logs -f [service-name]"
echo "  - Stop all:     docker-compose down"
echo ""
echo "🔐 Security Note:"
echo "  - JWT secret auto-generated and saved in .env"
echo "  - Configure Telegram via API: POST /telegram/register"
echo ""
