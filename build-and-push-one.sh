#!/bin/bash

# 단일 서비스 빌드 & Docker Hub 푸시
# 사용법: ./build-and-push-one.sh <서비스명>
# 예시:   ./build-and-push-one.sh product-service

set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

DOCKER_USERNAME="jybeomss1"

VALID_SERVICES=(
    "api-gateway"
    "order-service"
    "payment-service"
    "shipping-service"
    "notification-service"
    "user-service"
    "product-service"
    "settlement-service"
    "inventory-service"
)

usage() {
    echo "사용법: $0 <서비스명>"
    echo ""
    echo "사용 가능한 서비스:"
    for s in "${VALID_SERVICES[@]}"; do
        echo "  - $s"
    done
    exit 1
}

# 인자 확인
if [[ $# -ne 1 ]]; then
    usage
fi

SERVICE="$1"

# 유효한 서비스인지 확인
VALID=false
for s in "${VALID_SERVICES[@]}"; do
    [[ "$s" == "$SERVICE" ]] && VALID=true && break
done
if [[ "$VALID" == false ]]; then
    echo "❌ 알 수 없는 서비스: $SERVICE"
    echo ""
    usage
fi

IMAGE="${DOCKER_USERNAME}/${SERVICE}:latest"
DOCKERFILE="${SERVICE}/Dockerfile"

# Dockerfile 존재 확인
if [[ ! -f "$DOCKERFILE" ]]; then
    echo "❌ Dockerfile 을 찾을 수 없습니다: $DOCKERFILE"
    exit 1
fi

echo "🐳 Build & Push: ${SERVICE}"
echo "=========================================="
echo "Image : ${IMAGE}"
echo "=========================================="
echo ""

# 저장된 Docker 로그인은 push가 사용한다.
docker info >/dev/null

# 빌드
echo "🔨 빌드 중: ${SERVICE}..."
docker build -f "$DOCKERFILE" -t "$IMAGE" .
echo "✅ 빌드 완료: ${SERVICE}"
echo ""

# 푸시
echo "⬆️  푸시 중: ${IMAGE}..."
docker push "$IMAGE"
echo "✅ 푸시 완료: ${IMAGE}"
echo ""

echo "=========================================="
echo "✅ 완료!"
echo "  이미지: ${IMAGE}"
echo "  Docker Hub: https://hub.docker.com/r/${DOCKER_USERNAME}/${SERVICE}"
echo "=========================================="
