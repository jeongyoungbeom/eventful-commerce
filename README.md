# Eventful Commerce

이벤트 기반 마이크로서비스 아키텍처로 구현한 분산 커머스 백엔드

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=spring)](https://spring.io/projects/spring-boot)
[![Spring Batch](https://img.shields.io/badge/Spring%20Batch-5.x-6DB33F?logo=spring)](https://spring.io/projects/spring-batch)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis%20Cluster-7.0-DC382D?logo=redis)](https://redis.io/)
[![Kafka](https://img.shields.io/badge/Kafka-3.7-231F20?logo=apache-kafka)](https://kafka.apache.org/)

---

## 프로젝트 개요

판매자가 상품을 등록하고, 구매자가 주문-결제하면 자동으로 배송과 정산이 처리되는 분산 커머스 백엔드입니다.

**핵심 구현:**
- JWT 기반 인증/인가 (공통 모듈 `common-auth`)
- Outbox 패턴으로 이벤트 발행 신뢰성 보장
- CQRS — 상품 이벤트를 order-service가 로컬 읽기 모델로 동기화
- Redisson 분산락으로 주문 취소 동시성 제어
- Redis Lua 스크립트로 재고 원자적 처리
- Idempotency 패턴으로 중복 이벤트 방지
- DLQ (Dead Letter Queue) — Kafka 처리 실패 메시지 격리
- Spring Batch 일별 정산 배치 처리
- Telegram Bot 실시간 알림

---

## 아키텍처

```
┌──────────────────────────────────────────────────────────────────────┐
│                          Client                                      │
└──────────────┬──────────────────────────────────────────────────────┘
               │ JWT (Bearer Token)
               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       Application Layer                              │
│                                                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐            │
│  │  User    │  │ Product  │  │  Order   │  │ Payment  │            │
│  │  (8085)  │  │  (8086)  │  │  (8081)  │  │  (8082)  │            │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘            │
│                                                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                          │
│  │Shipping  │  │Notific.  │  │Settlement│                          │
│  │  (8083)  │  │  (8084)  │  │  (8087)  │                          │
│  └──────────┘  └──────────┘  └──────────┘                          │
└──────────────────────────┬───────────────────────────────────────────┘
                           │ Kafka Events
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    Kafka (KRaft 모드)                                │
│  • order-events       • payment-events                              │
│  • shipping-events    • product-events                              │
│  • *.DLT (Dead Letter Topics)                                       │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│                     Infrastructure Layer                             │
│  ┌─────────────────────┐   ┌────────────────────────────────────┐  │
│  │ PostgreSQL 16        │   │ Redis Cluster 7.0 (6 nodes)        │  │
│  │ • order_service      │   │ • 재고 관리 (Lua 스크립트)          │  │
│  │ • payment_service    │   │ • 분산락 (Redisson)                │  │
│  │ • shipping_service   │   │ • 토큰 블랙리스트 / Rate Limit     │  │
│  │ • notification_serv  │   └────────────────────────────────────┘  │
│  │ • user_service_db    │                                            │
│  │ • product_service_db │                                            │
│  │ • settlement_serv_db │                                            │
│  └─────────────────────┘                                            │
└──────────────────────────────────────────────────────────────────────┘
```

**전체 이벤트 플로우:**
```
[판매자] 상품 등록 (product-service)
  → product-events 발행
  → order-service: ProductReadModel 저장 + Redis 재고 초기화

[구매자] 주문 생성 (order-service)
  → ProductReadModel 검증 (ACTIVE 여부, 가격 조회)
  → Redis Lua 스크립트로 재고 예약
  → order-events(ORDER_RESERVED) 발행

→ payment-service: 결제 레코드 생성
→ [PG사] POST /payments/webhook → payment-events(PAYMENT_COMPLETED) 발행

→ order-service: 재고 확정 + ORDER_CONFIRMED 발행
→ shipping-service: 배송 생성 → SHIPPING_STARTED → SHIPPING_COMPLETED 발행
→ settlement-service: 정산 레코드 생성 (PENDING)
→ notification-service: 각 이벤트마다 Telegram 알림 전송

[매일 자정] Spring Batch
  → settlement-service: 전날 PENDING → CONFIRMED 일괄 처리
```

---

## 기술 스택

| 분류 | 기술 |
|---|---|
| Language | Kotlin 1.9.24 |
| Framework | Spring Boot 3.3.5 |
| Batch | Spring Batch 5.x |
| Security | Spring Security + JWT (JJWT 0.12.x) |
| Database | PostgreSQL 16 |
| Cache / Lock | Redis Cluster 7.0, Redisson 3.27.2 |
| Message Queue | Apache Kafka 3.7.0 (KRaft) |
| Notification | Telegram Bot API |
| Container | Docker, Docker Compose |

---

## 서비스 구성

| 서비스 | 포트 | 역할 |
|---|---|---|
| user-service | 8085 | 회원가입/로그인, JWT 발급, 판매자 등록 |
| product-service | 8086 | 상품 등록/수정/삭제 (SELLER 전용) |
| order-service | 8081 | 주문 생성/취소, 재고 관리, ProductReadModel |
| payment-service | 8082 | 결제 레코드 관리, PG 웹훅 처리 |
| shipping-service | 8083 | 배송 생성 및 상태 처리 |
| notification-service | 8084 | Kafka 이벤트 수신, Telegram 알림 |
| settlement-service | 8087 | 정산 생성, Spring Batch 일별 확정 |

---

## 핵심 구현

### 1. JWT 인증 (common-auth)

모든 서비스가 공유하는 JWT 인증 모듈입니다.

- `JwtTokenProvider` — 토큰 발급/검증
- `JwtAuthenticationFilter` — Bearer 토큰 추출 → SecurityContext 설정. `additionalValidation()` hook으로 서비스별 확장 가능
- user-service는 블랙리스트 체크를 추가로 수행

```kotlin
// user-service의 JwtAuthenticationFilter
@Component
class JwtAuthenticationFilter(
    jwtTokenProvider: JwtTokenProvider,
    private val tokenBlacklistService: TokenBlacklistService
) : com.eventfulcommerce.common.auth.JwtAuthenticationFilter(jwtTokenProvider) {

    override fun additionalValidation(token: String, response: HttpServletResponse): Boolean {
        if (tokenBlacklistService.isBlacklisted(token)) {
            response.sendError(401, "Token has been logged out")
            return false
        }
        return true
    }
}
```

### 2. Outbox 패턴

DB 트랜잭션과 이벤트 발행을 원자적으로 처리합니다.

```kotlin
@Transactional
fun orders(requests: List<OrdersRequest>, userId: UUID): List<String> {
    // 1. 주문 저장
    val savedOrders = ordersRepository.saveAll(orderList)

    // 2. Outbox에 이벤트 저장 (같은 트랜잭션)
    outboxEventService.record(events)

    // 3. OutboxPublisher(200ms 폴링) → Kafka 발행
}
```

### 3. CQRS — ProductReadModel

서비스 간 HTTP 호출 없이 주문 시 상품 정보를 검증합니다.

```
product-service → product-events 발행
  → order-service/ProductEventsConsumer 수신
    → ProductReadModel DB 저장 (로컬 읽기 모델)
    → Redis 재고 초기화

주문 생성 시:
  ProductReadModelRepository.findById(productId)  // HTTP 호출 없이 로컬 DB 조회
  → status == ACTIVE 검증
  → totalAmount = product.price * quantity 계산
```

### 4. Redis 재고 관리 (Lua 스크립트)

원자적 연산으로 재고 경합을 방지합니다. 상품 등록 이벤트 수신 시 자동 초기화됩니다.

```lua
-- reserve.lua
local stock = redis.call('GET', KEYS[1])
if tonumber(stock) > 0 then
    redis.call('DECR', KEYS[1])
    redis.call('SETEX', KEYS[2], ARGV[1], ARGV[2])  -- 예약 TTL 10분
    return 1
end
return 0
```

3가지 연산: `reserve` (예약) / `commit` (결제 확정) / `release` (취소/만료 복구)

### 5. 분산락 (Redisson)

주문 취소 동시성 문제를 해결합니다. `OrderCancelService`(락 관리)와 `OrderCancelExecutor`(트랜잭션)를 분리해 Spring AOP 프록시가 올바르게 동작합니다.

```kotlin
fun cancel(orderId: UUID, reason: String): Boolean {
    val lock = redissonClient.getLock("order:lock:$orderId")
    lock.tryLock(10, 30, TimeUnit.SECONDS)  // 최대 10초 대기, 30초 보유
    try {
        return orderCancelExecutor.execute(orderId, reason)  // @Transactional 분리
    } finally {
        lock.unlock()
    }
}
```

### 6. Idempotency (멱등성)

Kafka at-least-once 환경에서 중복 이벤트를 방지합니다.

```kotlin
fun <T> executeIdempotent(eventId: UUID, action: () -> T): IdempotencyResult<T> {
    try {
        processedEventRepository.save(ProcessedEvent(eventId))  // Unique Constraint
    } catch (e: DataIntegrityViolationException) {
        return IdempotencyResult.AlreadyProcessed  // save() 실패만 중복으로 판단
    }
    return IdempotencyResult.Success(action())  // action() 예외는 그대로 전파
}
```

### 7. DLQ (Dead Letter Queue)

Kafka 처리 실패 메시지를 격리합니다. 모든 consumer 서비스에 적용되어 있습니다.

```
처리 실패 → 1초 간격 3회 재시도 → 3회 모두 실패 → {topic}.DLT 격리
```

```kotlin
@Bean
fun defaultErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
    val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate)
    return DefaultErrorHandler(recoverer, FixedBackOff(1000L, 3L))
}
```

### 8. Spring Batch 정산 배치

매일 자정 전날 생성된 PENDING 정산을 1000건 단위 청크로 CONFIRMED 처리합니다.

```
PENDING 정산 (JpaPagingItemReader, pageSize=1000)
  → Settlement.confirm() (ItemProcessor)
  → saveAll() (ItemWriter)
  → Spring Batch 메타데이터에 실행 이력 저장
```

수수료율은 `application.yml`에서 환경별로 관리합니다.
```yaml
settlement:
  platform-fee-rate: 0.10  # 10%
  batch-cron: "0 0 0 * * *"
```

---

## Quick Start

### 사전 준비

**필수**
- Docker, Docker Compose
- `.env` 파일 (없으면 `pull-and-start.sh` 실행 시 자동 생성)

**선택 (Telegram 알림)**
```bash
# BotFather에서 봇 생성 후 .env에 추가
TELEGRAM_BOT_TOKEN=your-bot-token
TELEGRAM_BOT_USERNAME=your_bot_username
```

### 실행

```bash
# 1. 이미지 빌드 & Docker Hub 푸시 (소스 변경 시)
./build-and-push.sh

# 2. Pull & 전체 시스템 기동
./pull-and-start.sh

# 3. 서비스 상태 확인
curl http://localhost:8085/actuator/health  # user
curl http://localhost:8086/actuator/health  # product
curl http://localhost:8081/actuator/health  # order
curl http://localhost:8082/actuator/health  # payment
curl http://localhost:8083/actuator/health  # shipping
curl http://localhost:8084/actuator/health  # notification
curl http://localhost:8087/actuator/health  # settlement

# 4. 종료
./stop-all.sh
```

> **주의:** 재고는 상품 등록 시 자동으로 Redis에 초기화됩니다. 별도의 재고 초기화 스크립트는 필요 없습니다.

---

## API 문서

> 모든 API는 JWT Bearer 토큰이 필요합니다 (로그인 후 발급).
> 로그인 / 회원가입은 인증 불필요.

### User Service (8085)

```bash
# 구매자 회원가입
POST /auth/signup
{
  "email": "user@test.com",
  "password": "Test1234!",
  "name": "구매자"
}

# 판매자 회원가입
POST /auth/signup/seller
{
  "email": "seller@test.com",
  "password": "Test1234!",
  "name": "판매자",
  "businessName": "테스트 상점",
  "businessNumber": "123-45-67890",
  "bankAccount": "1234567890",
  "bankCode": "004"
}

# 로그인 → accessToken 발급
POST /auth/login
{ "email": "...", "password": "..." }

# 내 정보 조회 (본인만 가능)
GET /users/{userId}
Authorization: Bearer {accessToken}

# 로그아웃
POST /auth/logout
Authorization: Bearer {accessToken}

# Telegram chatId 등록 (알림 수신)
POST /telegram/register
Authorization: Bearer {accessToken}
{ "chatId": 123456789 }
```

### Product Service (8086) — SELLER 전용

```bash
# 상품 등록 (등록 시 Redis 재고 자동 초기화)
POST /products
Authorization: Bearer {seller_accessToken}
{
  "name": "상품명",
  "description": "상품 설명",
  "price": 10000,
  "stock": 100,
  "category": "ELECTRONICS"
}
# 카테고리: ELECTRONICS, CLOTHING, FOOD, BEAUTY, SPORTS, BOOKS, HOME, FLOWERS, ETC

# 상품 목록 조회 (카테고리 필터 가능)
GET /products?category=ELECTRONICS
Authorization: Bearer {accessToken}

# 내 상품 목록
GET /products/my
Authorization: Bearer {seller_accessToken}

# 상품 수정
PUT /products/{productId}
Authorization: Bearer {seller_accessToken}

# 재고 변경 (delta: 양수=증가, 음수=감소)
PATCH /products/{productId}/stock
Authorization: Bearer {seller_accessToken}
{ "delta": 50 }

# 상품 비활성화 (소프트 삭제)
DELETE /products/{productId}
Authorization: Bearer {seller_accessToken}
```

### Order Service (8081)

```bash
# 주문 생성 (userId는 JWT에서 추출, totalAmount는 서버에서 계산)
POST /orders
Authorization: Bearer {buyer_accessToken}
[
  { "productId": "uuid", "quantity": 2 }
]

# 주문 취소 (본인 주문만 가능, 권한 없으면 403)
POST /orders/{orderId}/cancel
Authorization: Bearer {buyer_accessToken}
```

### Payment Service (8082)

```bash
# PG사 결제 결과 수신 웹훅
POST /payments/webhook
{
  "orderId": "uuid",
  "result": "SUCCESS",  # or "FAIL"
  "pgTxId": "PG-TX-123"
}
```

### Settlement Service (8087)

```bash
# 내 정산 목록 (SELLER)
GET /settlements/my?status=PENDING
Authorization: Bearer {seller_accessToken}

# 내 정산 요약
GET /settlements/my/summary
Authorization: Bearer {seller_accessToken}

# 전체 정산 목록 (ADMIN)
GET /settlements?status=CONFIRMED
Authorization: Bearer {admin_accessToken}

# 정산 지급 완료 처리 (ADMIN, CONFIRMED → PAID)
PATCH /settlements/{settlementId}/pay
Authorization: Bearer {admin_accessToken}
```

---

## 수동 테스트 흐름

```bash
BASE_URL_USER="http://localhost:8085"
BASE_URL_PRODUCT="http://localhost:8086"
BASE_URL_ORDER="http://localhost:8081"
BASE_URL_PAYMENT="http://localhost:8082"
BASE_URL_SETTLEMENT="http://localhost:8087"

# 1. 판매자 회원가입 & 로그인
curl -X POST $BASE_URL_USER/auth/signup/seller \
  -H "Content-Type: application/json" \
  -d '{"email":"seller@test.com","password":"Test1234!","name":"판매자","businessName":"테스트상점","businessNumber":"123-45-67890","bankAccount":"1234567890","bankCode":"004"}'

SELLER_TOKEN=$(curl -s -X POST $BASE_URL_USER/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"seller@test.com","password":"Test1234!"}' | jq -r '.accessToken')

# 2. 상품 등록
PRODUCT_ID=$(curl -s -X POST $BASE_URL_PRODUCT/products \
  -H "Authorization: Bearer $SELLER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"테스트상품","description":"설명","price":10000,"stock":100,"category":"ELECTRONICS"}' \
  | jq -r '.productId')

# 잠시 대기 (product-events → order-service Redis 초기화)
sleep 3

# 3. 구매자 회원가입 & 로그인
curl -X POST $BASE_URL_USER/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"buyer@test.com","password":"Test1234!","name":"구매자"}'

BUYER_TOKEN=$(curl -s -X POST $BASE_URL_USER/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"buyer@test.com","password":"Test1234!"}' | jq -r '.accessToken')

# 4. 주문 생성
ORDER_ID=$(curl -s -X POST $BASE_URL_ORDER/orders \
  -H "Authorization: Bearer $BUYER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1}]" | jq -r '.[0]')

# 5. 결제 완료 웹훅
curl -X POST $BASE_URL_PAYMENT/payments/webhook \
  -H "Content-Type: application/json" \
  -d "{\"orderId\":\"$ORDER_ID\",\"result\":\"SUCCESS\",\"pgTxId\":\"PG-TX-001\"}"

# 6. 정산 확인
curl $BASE_URL_SETTLEMENT/settlements/my \
  -H "Authorization: Bearer $SELLER_TOKEN"
```

---

## 프로젝트 구조

```
eventful-commerce/
├── common-auth/            # JWT 공통 모듈 (JwtTokenProvider, JwtAuthFilter, UserRole)
├── common-outbox/          # Outbox 패턴 공통 모듈 + 이벤트 Payload 정의
├── common-idempotency/     # 멱등성 처리 공통 모듈
├── user-service/           # 인증/인가, 회원 관리 (8085)
├── product-service/        # 상품 관리 (8086)
├── order-service/          # 주문, 재고, ProductReadModel (8081)
├── payment-service/        # 결제, PG 웹훅 (8082)
├── shipping-service/       # 배송 (8083)
├── notification-service/   # Telegram 알림 (8084)
├── settlement-service/     # 정산, Spring Batch (8087)
├── docker-compose.yml
├── build-and-push.sh       # 전체 서비스 빌드 & Docker Hub 푸시
├── pull-and-start.sh       # 이미지 Pull & 전체 기동
├── stop-all.sh
└── scripts/
    ├── init-databases.sql  # PostgreSQL 데이터베이스 초기화
    └── init-redis-cluster.sh
```

---

## License

MIT
