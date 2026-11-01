#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

DOCKER_USERNAME="jybeomss1"
SERVICES=(api-gateway order-service payment-service shipping-service notification-service user-service product-service settlement-service inventory-service)

docker info >/dev/null

echo "🐳 전체 9개 서비스 빌드 & Docker Hub 푸시"
for service in "${SERVICES[@]}"; do
    echo "🔨 빌드: $service"
    docker build -f "$service/Dockerfile" -t "$DOCKER_USERNAME/$service:latest" .
done
for service in "${SERVICES[@]}"; do
    echo "⬆️  푸시: $service"
    docker push "$DOCKER_USERNAME/$service:latest"
done
echo "✅ 재고 서비스를 포함한 전체 이미지 빌드·푸시 완료"
