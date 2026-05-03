#!/bin/bash

# Docker Hub username
DOCKER_USERNAME="jybeomss1"

# Image tags
GATEWAY_IMAGE="${DOCKER_USERNAME}/api-gateway:latest"
ORDER_IMAGE="${DOCKER_USERNAME}/order-service:latest"
PAYMENT_IMAGE="${DOCKER_USERNAME}/payment-service:latest"
SHIPPING_IMAGE="${DOCKER_USERNAME}/shipping-service:latest"
NOTIFICATION_IMAGE="${DOCKER_USERNAME}/notification-service:latest"
USER_IMAGE="${DOCKER_USERNAME}/user-service:latest"
PRODUCT_IMAGE="${DOCKER_USERNAME}/product-service:latest"
SETTLEMENT_IMAGE="${DOCKER_USERNAME}/settlement-service:latest"

echo "🐳 Building and Pushing to Docker Hub"
echo "=========================================="
echo "Username: ${DOCKER_USERNAME}"
echo ""

# Check if logged in to Docker Hub
echo "🔐 Checking Docker Hub login..."
if ! docker info | grep -q "Username: ${DOCKER_USERNAME}"; then
    echo "⚠️  Not logged in to Docker Hub"
    echo "Please run: docker login"
    echo ""
    read -p "Do you want to login now? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        docker login
    else
        echo "❌ Aborted. Please login first: docker login"
        exit 1
    fi
fi

echo ""
echo "📦 Building images..."
echo ""

# Build api-gateway
echo "🔨 Building api-gateway..."
docker build -f api-gateway/Dockerfile -t ${GATEWAY_IMAGE} .
if [ $? -ne 0 ]; then
    echo "❌ Failed to build api-gateway"
    exit 1
fi
echo "✅ api-gateway built successfully"
echo ""

# Build order-service
echo "🔨 Building order-service..."
docker build -f order-service/Dockerfile -t ${ORDER_IMAGE} .
if [ $? -ne 0 ]; then
    echo "❌ Failed to build order-service"
    exit 1
fi
echo "✅ order-service built successfully"
echo ""

# Build payment-service
echo "🔨 Building payment-service..."
docker build -f payment-service/Dockerfile -t ${PAYMENT_IMAGE} .
if [ $? -ne 0 ]; then
    echo "❌ Failed to build payment-service"
    exit 1
fi
echo "✅ payment-service built successfully"
echo ""

# Build shipping-service
echo "🔨 Building shipping-service..."
docker build -f shipping-service/Dockerfile -t ${SHIPPING_IMAGE} .
if [ $? -ne 0 ]; then
    echo "❌ Failed to build shipping-service"
    exit 1
fi
echo "✅ shipping-service built successfully"
echo ""

echo "🔨 Building notification-service..."
docker build -f notification-service/Dockerfile -t ${NOTIFICATION_IMAGE} .
if [ $? -ne 0 ]; then
    echo "❌ Failed to build notification-service"
    exit 1
fi
echo "✅ notification-service built successfully"
echo ""

# Build product-service
echo "🔨 Building product-service..."
docker build -f product-service/Dockerfile -t ${PRODUCT_IMAGE} .
if [ $? -ne 0 ]; then
    echo "❌ Failed to build product-service"
    exit 1
fi
echo "✅ product-service built successfully"
echo ""

# Build settlement-service
echo "🔨 Building settlement-service..."
docker build -f settlement-service/Dockerfile -t ${SETTLEMENT_IMAGE} .
if [ $? -ne 0 ]; then
    echo "❌ Failed to build settlement-service"
    exit 1
fi
echo "✅ settlement-service built successfully"
echo ""

# Build user-service
echo "🔨 Building user-service..."
docker build -f user-service/Dockerfile -t ${USER_IMAGE} .
if [ $? -ne 0 ]; then
    echo "❌ Failed to build user-service"
    exit 1
fi
echo "✅ user-service built successfully"
echo ""

# Push to Docker Hub
echo "📤 Pushing images to Docker Hub..."
echo ""

echo "⬆️  Pushing api-gateway..."
docker push ${GATEWAY_IMAGE}
if [ $? -ne 0 ]; then
    echo "❌ Failed to push api-gateway"
    exit 1
fi
echo "✅ api-gateway pushed"
echo ""

echo "⬆️  Pushing order-service..."
docker push ${ORDER_IMAGE}
if [ $? -ne 0 ]; then
    echo "❌ Failed to push order-service"
    exit 1
fi
echo "✅ order-service pushed"
echo ""

echo "⬆️  Pushing payment-service..."
docker push ${PAYMENT_IMAGE}
if [ $? -ne 0 ]; then
    echo "❌ Failed to push payment-service"
    exit 1
fi
echo "✅ payment-service pushed"
echo ""

echo "⬆️  Pushing shipping-service..."
docker push ${SHIPPING_IMAGE}
if [ $? -ne 0 ]; then
    echo "❌ Failed to push shipping-service"
    exit 1
fi
echo "✅ shipping-service pushed"
echo ""

echo "⬆️  Pushing notification-service..."
docker push ${NOTIFICATION_IMAGE}
if [ $? -ne 0 ]; then
    echo "❌ Failed to push notification-service"
    exit 1
fi
echo "✅ notification-service pushed"
echo ""

echo "⬆️  Pushing product-service..."
docker push ${PRODUCT_IMAGE}
if [ $? -ne 0 ]; then
    echo "❌ Failed to push product-service"
    exit 1
fi
echo "✅ product-service pushed"
echo ""

echo "⬆️  Pushing settlement-service..."
docker push ${SETTLEMENT_IMAGE}
if [ $? -ne 0 ]; then
    echo "❌ Failed to push settlement-service"
    exit 1
fi
echo "✅ settlement-service pushed"
echo ""

echo "⬆️  Pushing user-service..."
docker push ${USER_IMAGE}
if [ $? -ne 0 ]; then
    echo "❌ Failed to push user-service"
    exit 1
fi
echo ""

echo "=========================================="
echo "✅ All images built and pushed successfully!"
echo ""
echo "📋 Images:"
echo "  - ${ORDER_IMAGE}"
echo "  - ${PAYMENT_IMAGE}"
echo "  - ${SHIPPING_IMAGE}"
echo "  - ${NOTIFICATION_IMAGE}"
echo "  - ${USER_IMAGE}"
echo ""
echo "🌐 View on Docker Hub:"
echo "  https://hub.docker.com/r/${DOCKER_USERNAME}/order-service"
echo "  https://hub.docker.com/r/${DOCKER_USERNAME}/payment-service"
echo "  https://hub.docker.com/r/${DOCKER_USERNAME}/shipping-service"
echo "  https://hub.docker.com/r/${DOCKER_USERNAME}/notification-service"
echo "  https://hub.docker.com/r/${DOCKER_USERNAME}/user-service"
echo ""
echo "✅ user-service pushed"
