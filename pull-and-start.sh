#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

DOCKER_USERNAME="jybeomss1"
SERVICES=(api-gateway order-service payment-service shipping-service notification-service user-service product-service settlement-service inventory-service)

docker info >/dev/null
docker compose version >/dev/null

if [[ ! -f .env ]]; then
    umask 077
    cat > .env <<'ENV'
CORS_ALLOWED_ORIGINS=http://localhost:3000
GMAIL_USERNAME=
GMAIL_APP_PASSWORD=
TELEGRAM_BOT_TOKEN=
TELEGRAM_BOT_USERNAME=
TELEGRAM_DEFAULT_USER_ID=
TELEGRAM_DEFAULT_CHAT_ID=
ENV
    echo "📝 .env 생성 완료. 메일 사용에 필요한 GMAIL_USERNAME/GMAIL_APP_PASSWORD를 설정하세요."
fi

chmod 600 .env

ensure_secret() {
    local key="$1" generated configured
    configured=$(sed -n "s/^${key}=//p" .env | tail -n 1)
    configured=${configured//[[:space:]]/}
    if [[ -n "${!key:-}" ]] || [[ -n "$configured" && "$configured" != '""' && "$configured" != "''" ]]; then
        return
    fi
    generated=$(openssl rand -base64 64 | tr -d '\r\n')
    if grep -q "^${key}=" .env; then
        sed -i "s|^${key}=.*|${key}=${generated}|" .env
    else
        printf '\n%s=%s\n' "$key" "$generated" >> .env
    fi
}
ensure_secret JWT_SECRET
ensure_secret INVENTORY_SERVICE_TOKEN

# 필수 환경 변수를 이미지 다운로드·컨테이너 변경 전에 확인한다.
docker compose config --quiet
for service in "${SERVICES[@]}"; do
    echo "📥 Pull: $service"
    docker pull "$DOCKER_USERNAME/$service:latest"
done

# 새 볼륨과 기존 볼륨 모두 지원하며 기존 DB·데이터는 유지한다.
docker compose up -d postgres kafka redis-node-{1..6}
postgres_attempts=0
until docker compose exec -T postgres pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1; do
    postgres_attempts=$((postgres_attempts + 1))
    if (( postgres_attempts >= 30 )); then
        echo "❌ PostgreSQL이 준비되지 않았습니다. docker compose logs postgres를 확인하세요." >&2
        exit 1
    fi
    sleep 2
done
docker compose exec -T postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 <<'SQL'
SELECT 'CREATE DATABASE inventory_service'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'inventory_service')\gexec
SQL
bash scripts/init-redis-cluster.sh

docker compose up -d --no-build
docker compose ps
echo "✅ 재고 서비스를 포함한 전체 컨테이너 기동 요청 완료"
echo "관리자 화면: http://localhost/api/inventory/admin/index.html"
echo "로그 확인: docker compose logs -f inventory-service order-service"
