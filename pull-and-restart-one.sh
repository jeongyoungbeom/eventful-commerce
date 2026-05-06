#!/bin/bash

# 단일 서비스 이미지 pull & 재시작
# 사용법: ./pull-and-restart-one.sh <서비스명>
# 예시:   ./pull-and-restart-one.sh product-service

set -euo pipefail

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

echo "🐳 Pull & Restart: ${SERVICE}"
echo "=========================================="
echo "Image : ${IMAGE}"
echo "=========================================="
echo ""

# .env 파일 확인
if [[ ! -f .env ]]; then
    echo "⚠️  .env 파일이 없습니다. pull-and-start.sh 를 먼저 실행하거나 .env 파일을 생성하세요."
    exit 1
fi

# pull 전 현재 이미지 ID 저장
OLD_IMAGE_ID=$(docker inspect --format='{{.Image}}' "$SERVICE" 2>/dev/null || echo "")

# 최신 이미지 pull
echo "📥 이미지 pull 중: ${IMAGE}..."
docker pull "$IMAGE"
NEW_IMAGE_ID=$(docker inspect --format='{{.Id}}' "$IMAGE")
echo "✅ Pull 완료"
echo ""

# 서비스에 연관된 모든 컨테이너 제거 (이름이 다른 오래된 컨테이너 포함)
echo "🔄 재시작 중: ${SERVICE}..."
docker ps -a --filter "label=com.docker.compose.service=${SERVICE}" --format "{{.ID}}" \
    | xargs -r docker rm -f
docker-compose up -d --no-deps "$SERVICE"
echo "✅ 재시작 완료"
echo ""

# 이전 이미지 삭제 (교체된 경우에만)
if [[ -n "$OLD_IMAGE_ID" && "$OLD_IMAGE_ID" != "$NEW_IMAGE_ID" ]]; then
    echo "🗑️  이전 이미지 삭제 중: ${OLD_IMAGE_ID:0:19}..."
    docker rmi "$OLD_IMAGE_ID" 2>/dev/null && echo "✅ 이전 이미지 삭제 완료" || echo "⚠️  이전 이미지 삭제 실패 (다른 컨테이너에서 사용 중일 수 있음)"
    echo ""
fi

# 컨테이너 상태 확인
echo "📊 컨테이너 상태:"
docker-compose ps "$SERVICE"
echo ""

echo "=========================================="
echo "✅ 완료! 로그 확인: docker-compose logs -f ${SERVICE}"
echo "=========================================="
