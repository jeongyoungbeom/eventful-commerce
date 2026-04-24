#!/bin/bash

# Docker Hub username
DOCKER_USERNAME="jybeomss1"

# Image tags
ORDER_IMAGE="${DOCKER_USERNAME}/order-service:latest"
PAYMENT_IMAGE="${DOCKER_USERNAME}/payment-service:latest"
SHIPPING_IMAGE="${DOCKER_USERNAME}/shipping-service:latest"

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

# Push to Docker Hub
echo "📤 Pushing images to Docker Hub..."
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

echo "=========================================="
echo "✅ All images built and pushed successfully!"
echo ""
echo "📋 Images:"
echo "  - ${ORDER_IMAGE}"
echo "  - ${PAYMENT_IMAGE}"
echo "  - ${SHIPPING_IMAGE}"
echo ""
echo "🌐 View on Docker Hub:"
echo "  https://hub.docker.com/r/${DOCKER_USERNAME}/order-service"
echo "  https://hub.docker.com/r/${DOCKER_USERNAME}/payment-service"
echo "  https://hub.docker.com/r/${DOCKER_USERNAME}/shipping-service"
echo ""
