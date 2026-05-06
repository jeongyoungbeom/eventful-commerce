#!/bin/bash

# =============================================
# 상품 플로우 통합 테스트
# Flow 1: 판매자 회원가입 -> 로그인 -> 상품 등록
# Flow 2: 사용자 회원가입 -> 로그인 -> 상품 목록/상세 조회
# =============================================

set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
TIMESTAMP=$(date +%s)

# --- 색상 출력 ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
RESET='\033[0m'

step()  { echo -e "\n${CYAN}${BOLD}[STEP]${RESET} $1"; }
ok()    { echo -e "${GREEN}[OK]${RESET}   $1"; }
fail()  { echo -e "${RED}[FAIL]${RESET} $1"; exit 1; }
info()  { echo -e "${YELLOW}[INFO]${RESET} $1"; }

# jq 설치 여부 확인
if ! command -v jq &>/dev/null; then
    echo -e "${RED}jq 가 설치되어 있지 않습니다. 'sudo apt-get install -y jq' 로 설치 후 재실행하세요.${RESET}"
    exit 1
fi

# HTTP 요청 헬퍼 (응답 바디와 HTTP 상태코드를 함께 반환)
call() {
    local method="$1"; local url="$2"; local data="${3:-}"; local token="${4:-}"
    local auth_header=""
    [[ -n "$token" ]] && auth_header="-H \"Authorization: Bearer $token\""

    local response
    response=$(curl -s -w "\n__STATUS__%{http_code}" \
        -X "$method" "$url" \
        -H "Content-Type: application/json" \
        ${token:+-H "Authorization: Bearer $token"} \
        ${data:+-d "$data"})

    RESPONSE_BODY=$(echo "$response" | sed '$d')
    RESPONSE_STATUS=$(echo "$response" | tail -1 | sed 's/__STATUS__//')
}

echo ""
echo -e "${BOLD}================================================${RESET}"
echo -e "${BOLD}  상품 플로우 통합 테스트  (timestamp: ${TIMESTAMP})${RESET}"
echo -e "${BOLD}================================================${RESET}"
info "Gateway: $GATEWAY_URL"

# =============================================
# Flow 1: 판매자
# =============================================
echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${BOLD}  Flow 1: 판매자 회원가입 → 로그인 → 상품 등록${RESET}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"

SELLER_EMAIL="seller_${TIMESTAMP}@test.com"
SELLER_PASSWORD="Test1234!"
SELLER_NAME="테스트판매자"

# 1. 판매자 회원가입
step "판매자 회원가입"
info "email: $SELLER_EMAIL"
call POST "$GATEWAY_URL/api/auth/signup/seller" \
    "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$SELLER_PASSWORD\",\"name\":\"$SELLER_NAME\",\"businessName\":\"테스트상점\",\"businessNumber\":\"123-45-67890\",\"bankAccount\":\"110-123-456789\",\"bankCode\":\"088\"}"

if [[ "$RESPONSE_STATUS" == "200" || "$RESPONSE_STATUS" == "201" ]]; then
    ok "판매자 회원가입 성공 (HTTP $RESPONSE_STATUS)"
else
    info "응답: $RESPONSE_BODY"
    fail "판매자 회원가입 실패 (HTTP $RESPONSE_STATUS)"
fi

# 2. 판매자 로그인
step "판매자 로그인"
call POST "$GATEWAY_URL/api/auth/login/seller" \
    "{\"email\":\"$SELLER_EMAIL\",\"password\":\"$SELLER_PASSWORD\"}"

if [[ "$RESPONSE_STATUS" == "200" ]]; then
    SELLER_TOKEN=$(echo "$RESPONSE_BODY" | jq -r '.accessToken')
    SELLER_ID=$(echo "$RESPONSE_BODY" | jq -r '.userId')
    ok "판매자 로그인 성공 (HTTP $RESPONSE_STATUS)"
    info "userId: $SELLER_ID"
    info "accessToken: ${SELLER_TOKEN:0:40}..."
else
    info "응답: $RESPONSE_BODY"
    fail "판매자 로그인 실패 (HTTP $RESPONSE_STATUS)"
fi

# 3. 상품 등록
step "상품 등록"
call POST "$GATEWAY_URL/api/products" \
    "{\"name\":\"테스트 상품\",\"description\":\"테스트용 상품 설명입니다\",\"price\":29000,\"stock\":100,\"category\":\"ELECTRONICS\"}" \
    "$SELLER_TOKEN"

if [[ "$RESPONSE_STATUS" == "201" ]]; then
    PRODUCT_ID=$(echo "$RESPONSE_BODY" | jq -r '.productId')
    PRODUCT_NAME=$(echo "$RESPONSE_BODY" | jq -r '.name')
    PRODUCT_PRICE=$(echo "$RESPONSE_BODY" | jq -r '.price')
    PRODUCT_STOCK=$(echo "$RESPONSE_BODY" | jq -r '.stock')
    ok "상품 등록 성공 (HTTP $RESPONSE_STATUS)"
    info "productId:    $PRODUCT_ID"
    info "name:         $PRODUCT_NAME"
    info "price:        ${PRODUCT_PRICE}원"
    info "stock:        ${PRODUCT_STOCK}개"
else
    info "응답: $RESPONSE_BODY"
    fail "상품 등록 실패 (HTTP $RESPONSE_STATUS)"
fi

# 4. 내 상품 목록 조회 (판매자)
step "내 상품 목록 조회 (판매자 전용)"
call GET "$GATEWAY_URL/api/products/my" "" "$SELLER_TOKEN"

if [[ "$RESPONSE_STATUS" == "200" ]]; then
    MY_PRODUCT_COUNT=$(echo "$RESPONSE_BODY" | jq 'length')
    ok "내 상품 목록 조회 성공 (HTTP $RESPONSE_STATUS) — ${MY_PRODUCT_COUNT}개"
else
    info "응답: $RESPONSE_BODY"
    fail "내 상품 목록 조회 실패 (HTTP $RESPONSE_STATUS)"
fi

# =============================================
# Flow 2: 사용자
# =============================================
echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${BOLD}  Flow 2: 사용자 회원가입 → 로그인 → 상품 조회${RESET}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"

USER_EMAIL="user_${TIMESTAMP}@test.com"
USER_PASSWORD="Test1234!"
USER_NAME="테스트사용자"

# 5. 사용자 회원가입
step "사용자 회원가입"
info "email: $USER_EMAIL"
call POST "$GATEWAY_URL/api/auth/signup/user" \
    "{\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\",\"name\":\"$USER_NAME\"}"

if [[ "$RESPONSE_STATUS" == "200" || "$RESPONSE_STATUS" == "201" ]]; then
    ok "사용자 회원가입 성공 (HTTP $RESPONSE_STATUS)"
else
    info "응답: $RESPONSE_BODY"
    fail "사용자 회원가입 실패 (HTTP $RESPONSE_STATUS)"
fi

# 6. 사용자 로그인
step "사용자 로그인"
call POST "$GATEWAY_URL/api/auth/login/user" \
    "{\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\"}"

if [[ "$RESPONSE_STATUS" == "200" ]]; then
    USER_TOKEN=$(echo "$RESPONSE_BODY" | jq -r '.accessToken')
    USER_ID=$(echo "$RESPONSE_BODY" | jq -r '.userId')
    ok "사용자 로그인 성공 (HTTP $RESPONSE_STATUS)"
    info "userId: $USER_ID"
    info "accessToken: ${USER_TOKEN:0:40}..."
else
    info "응답: $RESPONSE_BODY"
    fail "사용자 로그인 실패 (HTTP $RESPONSE_STATUS)"
fi

# 7. 전체 상품 목록 조회 (비로그인 가능)
step "전체 상품 목록 조회 (비로그인)"
call GET "$GATEWAY_URL/api/products"

if [[ "$RESPONSE_STATUS" == "200" ]]; then
    TOTAL_COUNT=$(echo "$RESPONSE_BODY" | jq 'length')
    ok "전체 상품 목록 조회 성공 (HTTP $RESPONSE_STATUS) — ${TOTAL_COUNT}개"
else
    info "응답: $RESPONSE_BODY"
    fail "전체 상품 목록 조회 실패 (HTTP $RESPONSE_STATUS)"
fi

# 8. 카테고리 필터 조회 (비로그인 가능)
step "카테고리 필터 조회 (ELECTRONICS, 비로그인)"
call GET "$GATEWAY_URL/api/products?category=ELECTRONICS"

if [[ "$RESPONSE_STATUS" == "200" ]]; then
    ELECTRONICS_COUNT=$(echo "$RESPONSE_BODY" | jq 'length')
    ok "카테고리 필터 조회 성공 (HTTP $RESPONSE_STATUS) — ${ELECTRONICS_COUNT}개"
else
    info "응답: $RESPONSE_BODY"
    fail "카테고리 필터 조회 실패 (HTTP $RESPONSE_STATUS)"
fi

# 9. 상품 단건 조회 (비로그인 가능 — 위에서 등록한 상품)
step "상품 단건 조회 (비로그인)"
info "productId: $PRODUCT_ID"
call GET "$GATEWAY_URL/api/products/$PRODUCT_ID"

if [[ "$RESPONSE_STATUS" == "200" ]]; then
    FETCHED_NAME=$(echo "$RESPONSE_BODY" | jq -r '.name')
    FETCHED_STATUS=$(echo "$RESPONSE_BODY" | jq -r '.status')
    ok "상품 단건 조회 성공 (HTTP $RESPONSE_STATUS)"
    info "name:   $FETCHED_NAME"
    info "status: $FETCHED_STATUS"
else
    info "응답: $RESPONSE_BODY"
    fail "상품 단건 조회 실패 (HTTP $RESPONSE_STATUS)"
fi

# 10. 상품 단건 조회 — 로그인 상태
step "상품 단건 조회 (로그인 상태)"
call GET "$GATEWAY_URL/api/products/$PRODUCT_ID" "" "$USER_TOKEN"

if [[ "$RESPONSE_STATUS" == "200" ]]; then
    ok "로그인 상태 상품 조회 성공 (HTTP $RESPONSE_STATUS)"
else
    info "응답: $RESPONSE_BODY"
    fail "로그인 상태 상품 조회 실패 (HTTP $RESPONSE_STATUS)"
fi

# =============================================
# 결과 요약
# =============================================
echo ""
echo -e "${BOLD}================================================${RESET}"
echo -e "${GREEN}${BOLD}  모든 테스트 통과${RESET}"
echo -e "${BOLD}================================================${RESET}"
echo -e "  판매자 email : $SELLER_EMAIL"
echo -e "  사용자 email : $USER_EMAIL"
echo -e "  등록 상품 ID  : $PRODUCT_ID"
echo ""
