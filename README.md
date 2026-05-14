# Eventful Commerce

이벤트 기반 MSA로 구현한 분산 커머스 백엔드 — 재고·주문·결제·정산·알림 전 과정을 서비스 간 직접 호출 없이 Kafka 이벤트로 연결합니다.

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=spring)](https://spring.io/projects/spring-boot)
[![Spring Cloud Gateway](https://img.shields.io/badge/Spring%20Cloud%20Gateway-MVC-6DB33F?logo=spring)](https://spring.io/projects/spring-cloud-gateway)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis%20Cluster-7.0-DC382D?logo=redis)](https://redis.io/)
[![Kafka](https://img.shields.io/badge/Kafka-3.7%20KRaft-231F20?logo=apache-kafka)](https://kafka.apache.org/)
[![Docker](https://img.shields.io/badge/Docker%20Compose-ready-2496ED?logo=docker)](https://www.docker.com/)

---

## 프로젝트 동기

### 전 직장 경험에서의 교훈

전 직장에서는 모놀리스 구조로 개발했는데, 여러 기능들이 Controller 단위로만 나뉘어 있었습니다. A 기능이 B를 호출하고, B 기능이 다시 A를 참조하다 보니 결합도가 매우 높아졌고, 한 부분을 수정할 때 예상치 못한 곳에서 사이드 이펙트가 발생하는 문제를 반복적으로 겪었습니다.

이 경험을 바탕으로 **"각 도메인이 단일 책임을 갖고 독립적으로 운영되는 구조"** 를 직접 설계하고 구현해보고 싶었습니다.

### MSA를 선택한 이유

- **결합도 최소화**: 각 서비스가 자신의 도메인에만 집중하고, 데이터베이스도 서비스별로 분리
- **독립적 배포·확장**: 결제 트래픽이 급증해도 payment-service만 스케일 아웃 가능
- **이커머스와의 적합성**: 주문·결제·재고·배송·정산은 각자 명확한 도메인 경계를 가지며, 실제 대형 이커머스 플랫폼도 이 방식을 채택

### 핵심 설계 목표

MSA로 전환하면서 필연적으로 마주치는 세 가지 문제를 직접 해결하는 것을 목표로 삼았습니다.

| 도전 | 문제 상황 | 선택한 해결책 |
|---|---|---|
| **동시성 제어** | 재고 차감·주문 취소 동시 요청 시 데이터 정합성 깨짐 | Redis Lua 스크립트(원자적 재고 처리) + Redisson 분산락(취소 직렬화) |
| **멱등성 보장** | Kafka at-least-once 환경에서 동일 이벤트 중복 소비 | ProcessedEvent + Unique Constraint → DataIntegrityViolationException으로 중복 판단 |
| **분산 트랜잭션 (Saga)** | 주문→결제→배송→정산이 각기 다른 DB를 사용 | Choreography-based Saga — Outbox 패턴으로 이벤트 신뢰성 보장 후 각 서비스가 이벤트를 구독해 자율 처리 |

---

## 아키텍처

### 전체 요청 흐름

```
[외부 인터넷]
     │
     │ (포트포워딩 9090 → 8080)
     ▼
┌──────────────────────────────────┐
│  Nginx (80)                      │
│  • CORS 처리                     │
│  • API Gateway로 리버스 프록시    │
└──────────────────┬───────────────┘
                   │
                   ▼
┌──────────────────────────────────┐
│  API Gateway (8080)              │
│  • JWT 검증 (GatewayJwtFilter)   │
│  • /api/** 라우팅                 │
│  • Rate Limit (Redis)            │
└──────┬───────────────────────────┘
       │
       ├──────────────────────────────────────────────────┐
       │                                                  │
       ▼                                                  ▼
┌─────────────────────────────────────┐     ┌────────────────────────┐
│  동기 요청 서비스                     │     │  이벤트 구독 서비스      │
│                                     │     │                        │
│  User Service       (8085)          │     │  Notification  (8084)  │
│  Product Service    (8086)          │     │  Shipping      (8083)  │
│  Order Service      (8081)          │     │  Settlement    (8087)  │
│  Payment Service    (8082)          │     └─────────┬──────────────┘
└────────────┬────────────────────────┘               │
             │ Kafka Events (Outbox Pattern)           │
             ▼                                         │
┌────────────────────────────────────┐                 │
│  Apache Kafka (KRaft, 3 brokers)   │◄────────────────┘
│                                    │
│  order-events    payment-events    │
│  product-events  shipping-events   │
│  *.DLT (Dead Letter Topics)        │
└────────────────────────────────────┘

┌────────────────────────────────────────────────────────────┐
│  Infrastructure                                            │
│                                                            │
│  PostgreSQL 16 (서비스별 독립 DB)    Redis Cluster 7.0     │
│  • order_service_db                  (6 nodes)            │
│  • payment_service_db                • 재고 (Lua Script)  │
│  • product_service_db                • 분산락 (Redisson)  │
│  • user_service_db                   • Refresh Token      │
│  • shipping_service_db               • Rate Limit         │
│  • settlement_service_db                                   │
│  • notification_service_db                                 │
└────────────────────────────────────────────────────────────┘
```

### 이벤트 플로우

```
[판매자] 상품 등록 (product-service)
  └─► product-events(PRODUCT_CREATED) 발행
        └─► order-service: ProductReadModel 저장 + Redis 재고 초기화

[구매자] 주문 생성 (order-service)
  └─► ProductReadModel 로컬 조회 (서비스 간 HTTP 호출 없음)
  └─► Redis Lua 스크립트로 재고 예약 (원자적)
  └─► order-events(ORDER_RESERVED) 발행
        └─► payment-service: 결제 레코드 생성

[PG사 웹훅] POST /payments/webhook
  └─► payment-events(PAYMENT_COMPLETED) 발행
        └─► order-service: 재고 확정 + ORDER_CONFIRMED 발행
              └─► shipping-service: 배송 생성 → SHIPPING_STARTED → SHIPPING_COMPLETED
              └─► settlement-service: 정산 레코드 생성 (PENDING)
        └─► notification-service: 각 이벤트마다 Telegram 알림 전송

[매일 자정] Spring Batch
  └─► settlement-service: PENDING → CONFIRMED 일괄 처리 (청크 1000건)
```

---

## 기술 스택

| 분류 | 기술 |
|---|---|
| Language | Kotlin 1.9.24 |
| Framework | Spring Boot 3.3.5 |
| API Gateway | Spring Cloud Gateway MVC |
| Batch | Spring Batch 5.x |
| Security | Spring Security + JWT (JJWT 0.12.x) |
| Database | PostgreSQL 16 (서비스별 독립 DB) |
| Cache / Lock | Redis Cluster 7.0, Redisson 3.27.2 |
| Message Queue | Apache Kafka 3.7.0 (KRaft 모드) |
| Notification | Telegram Bot API |
| Infrastructure | Docker, Docker Compose, Nginx |
| CI/CD | GitHub Actions |

---

## 서비스 구성

| 서비스 | 포트 | 역할 |
|---|---|---|
| **api-gateway** | 8080 | JWT 검증, 라우팅 (`/api/**`), Rate Limit |
| **user-service** | 8085 | 회원가입/로그인, JWT 발급, 판매자 등록, 토큰 블랙리스트 |
| **product-service** | 8086 | 상품 등록/수정/삭제 (SELLER 전용) |
| **order-service** | 8081 | 주문 생성/취소, 재고 관리, ProductReadModel |
| **payment-service** | 8082 | 결제 레코드 관리, PG 웹훅 처리 |
| **shipping-service** | 8083 | 배송 생성 및 상태 처리 |
| **notification-service** | 8084 | Kafka 이벤트 수신, Telegram 알림 전송 |
| **settlement-service** | 8087 | 정산 생성, Spring Batch 일별 확정 |

공통 모듈: `common-auth` (JWT 공통), `common-outbox` (Outbox + 이벤트 Payload), `common-idempotency` (멱등성 처리)

---

## 주요 구현

### 1. API Gateway — 단일 진입점과 JWT 검증

모든 외부 요청은 api-gateway를 통해 들어옵니다. `GatewayJwtFilter`가 요청마다 JWT를 검증하고 `X-User-Id`, `X-User-Role` 헤더를 하위 서비스에 전달합니다. 각 서비스는 이 헤더를 신뢰하므로 자체 JWT 파싱 없이 인가 처리가 가능합니다.

```
Client → api-gateway(JWT 검증) → X-User-Id / X-User-Role 헤더 주입 → 하위 서비스
```

공통 인증 모듈(`common-auth`)은 `JwtAuthenticationFilter`의 `additionalValidation()` hook을 제공합니다. user-service는 이를 오버라이드해 토큰 블랙리스트 체크를 추가합니다.

```kotlin
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

### 2. Outbox 패턴 — 이벤트 발행 신뢰성

DB 저장과 이벤트 발행은 원자적으로 처리되어야 합니다. 이를 위해 이벤트를 Kafka에 직접 보내지 않고 같은 트랜잭션 내에 Outbox 테이블에 기록합니다. 별도 `OutboxPublisher`(200ms 폴링)가 미발행 이벤트를 읽어 Kafka로 전송합니다.

```kotlin
@Transactional
fun orders(requests: List<OrdersRequest>, userId: UUID): List<String> {
    val savedOrders = ordersRepository.saveAll(orderList)
    outboxEventService.record(events)   // 같은 트랜잭션 — DB 저장 실패 시 이벤트도 롤백
}
// OutboxPublisher: @Scheduled(fixedDelay = 200) → Kafka 발행 후 published = true
```

### 3. CQRS — ProductReadModel

주문 시 상품 가격·상태를 검증하기 위해 product-service를 HTTP로 호출하면 두 서비스가 결합됩니다. 대신 product-events를 order-service가 구독해 로컬 읽기 모델로 동기화합니다.

```
product-service → product-events → order-service/ProductEventsConsumer
  → ProductReadModel DB 저장  +  Redis 재고 초기화

주문 생성 시:
  ProductReadModelRepository.findById(productId)  // 로컬 DB 조회, HTTP 없음
  → status == ACTIVE 검증
  → totalAmount = price × quantity 계산
```

### 4. Redis 재고 관리 — Lua 스크립트

재고 차감은 확인(GET)과 변경(DECR)이 분리되면 경합 조건이 생깁니다. Lua 스크립트로 두 연산을 원자적으로 묶어 해결합니다.

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

세 가지 연산: `reserve` (주문 예약) / `commit` (결제 확정 후 영구 차감) / `release` (취소·만료 시 복구)

### 5. Redisson 분산락 — 주문 취소 직렬화

동일 주문에 취소 요청이 동시에 들어오면 중복 취소가 발생합니다. Redisson 분산락으로 직렬화하고, `@Transactional`이 프록시로 동작하도록 락 관리(`OrderCancelService`)와 트랜잭션(`OrderCancelExecutor`)을 클래스로 분리합니다.

```kotlin
fun cancel(orderId: UUID, reason: String): Boolean {
    val lock = redissonClient.getLock("order:lock:$orderId")
    val acquired = lock.tryLock(10, 30, TimeUnit.SECONDS)  // 최대 10초 대기, 30초 보유
    if (!acquired) return false
    try {
        return orderCancelExecutor.execute(orderId, reason)  // @Transactional 분리
    } finally {
        lock.unlock()
    }
}
```

### 6. Idempotency — 중복 이벤트 방지

Kafka at-least-once delivery 특성상 같은 이벤트가 재전송될 수 있습니다. `ProcessedEvent` 테이블의 Unique Constraint를 활용해 중복을 판단합니다. `save()` 실패(DataIntegrityViolationException)만 중복으로 간주하고, 비즈니스 로직 예외는 그대로 전파해 DLQ로 격리합니다.

```kotlin
fun <T> executeIdempotent(eventId: UUID, action: () -> T): IdempotencyResult<T> {
    try {
        processedEventRepository.save(ProcessedEvent(eventId))
    } catch (e: DataIntegrityViolationException) {
        return IdempotencyResult.AlreadyProcessed  // 중복 이벤트
    }
    return IdempotencyResult.Success(action())  // action() 예외는 호출자(Kafka consumer)가 처리
}
```

### 7. DLQ — 처리 실패 메시지 격리

모든 consumer 서비스에 동일한 에러 핸들러를 적용합니다. 3회 재시도 후에도 실패하면 `{topic}.DLT`로 격리되어 정상 처리 흐름에 영향을 주지 않습니다.

```kotlin
@Bean
fun defaultErrorHandler(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler {
    val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate)
    return DefaultErrorHandler(recoverer, FixedBackOff(1000L, 3L))  // 1초 간격 3회
}
```

### 8. Spring Batch 정산 배치

매일 자정 전날 생성된 PENDING 정산을 1000건 단위 청크로 처리합니다. 수수료율은 환경별 `application.yml`로 관리합니다.

```
PENDING 정산 (JpaPagingItemReader, pageSize=1000)
  → Settlement.confirm()   (ItemProcessor)
  → saveAll()              (ItemWriter)
  → Spring Batch 메타데이터에 실행 이력 저장
```

```yaml
settlement:
  platform-fee-rate: 0.10
  batch-cron: "0 0 0 * * *"
```

---

## 트러블슈팅

### 1. 재고 초과 판매 — 동시성 문제

**문제 발견**

처음 구현에서는 재고 차감을 단순 DB UPDATE로 처리했습니다. 단건 요청에서는 정상 동작했지만, 재고 10개에 50개의 동시 요청을 보내자 10개를 초과한 주문이 생성되는 현상을 확인했습니다.

```
재고: 10개 / 동시 요청: 50개
→ 기대: 성공 10건, 실패 40건
→ 실제: 성공 12~15건 (경쟁 조건으로 초과 판매)
```

**원인 분석**

DB의 SELECT(재고 확인) → UPDATE(재고 차감) 사이에 다른 스레드가 끼어들어 같은 재고를 동시에 차감했습니다. DB 낙관적 락으로도 해결 가능하지만, 이커머스 재고처럼 고빈도 연산에서는 충돌이 잦아 재시도 오버헤드가 큽니다.

**해결**

Redis Lua 스크립트로 확인과 차감을 단일 원자적 연산으로 묶었습니다. Redis는 싱글 스레드로 Lua를 실행하기 때문에 별도 락 없이 원자성이 보장됩니다.

```
재고: 10개 / 동시 요청: 50개
→ 테스트 결과: 성공 10건, 실패 40건, 잔여 재고 0건 (정확)
```

> 관련 테스트: `ConcurrencyStressTest` — `should prevent overselling when concurrent orders exceed stock`

---

**주문 취소 중복 처리**

같은 주문에 동시 취소 요청이 2건 들어오면 둘 다 CANCEL 상태 확인을 통과해 취소가 두 번 실행되었습니다. Redisson 분산락으로 주문 단위 직렬화를 적용해 해결했습니다. 이때 `@Transactional`과 락을 같은 클래스에 두면 Spring AOP 프록시가 올바르게 동작하지 않아 `OrderCancelService`(락)와 `OrderCancelExecutor`(트랜잭션)로 클래스를 분리했습니다.

---

### 2. Kafka 중복 소비 — 멱등성 문제

**문제 발견**

Kafka는 at-least-once delivery를 기본으로 합니다. 네트워크 지연이나 consumer 재시작 시 같은 이벤트가 두 번 소비될 수 있고, 이를 처리하지 않으면 결제 완료 이벤트 중복 처리로 동일 주문이 두 번 확정되거나 정산이 두 번 생성되는 문제가 발생합니다.

```
PAYMENT_COMPLETED 이벤트 2회 수신
→ order-service: ORDER_CONFIRMED 두 번 실행
→ settlement-service: 정산 레코드 중복 생성
```

**해결**

`ProcessedEvent` 테이블에 `eventId`를 Unique Constraint로 저장합니다. 중복 이벤트가 들어오면 `save()`에서 `DataIntegrityViolationException`이 발생하고 즉시 `AlreadyProcessed`를 반환합니다. 이 방식의 핵심은 **비즈니스 로직 예외와 중복 판단을 완전히 분리**한 점입니다. `action()` 내부에서 발생한 예외는 그대로 전파되어 Kafka가 재시도하거나 DLQ로 격리합니다.

```
동일 eventId로 10개 스레드 동시 처리
→ 테스트 결과: commit() 1회, save(order) 1회, outbox record 1회
```

> 관련 테스트: `IdempotencyTest` — `should handle concurrent duplicate payment events once`

---

## Quick Start

### 사전 준비

- Docker, Docker Compose
- `.env` 파일 (없으면 `pull-and-start.sh` 실행 시 자동 생성)

```bash
# Telegram 알림 사용 시 .env에 추가 (선택)
TELEGRAM_BOT_TOKEN=your-bot-token
TELEGRAM_BOT_USERNAME=your_bot_username
```

### 실행

```bash
# 소스 변경 시: 빌드 & Docker Hub 푸시
./build-and-push.sh

# 전체 시스템 기동
./pull-and-start.sh

# 헬스 체크
curl http://localhost:8080/actuator/health  # api-gateway
curl http://localhost:8085/actuator/health  # user
curl http://localhost:8086/actuator/health  # product
curl http://localhost:8081/actuator/health  # order
curl http://localhost:8082/actuator/health  # payment

# 종료
./stop-all.sh
```

> 상품 등록 시 Redis 재고가 자동 초기화됩니다. 별도 초기화 스크립트는 필요 없습니다.

---

## API 문서

> 모든 요청은 api-gateway(`localhost:8080`)를 통해 `/api/` 접두사로 호출합니다.  
> 로그인/회원가입을 제외한 모든 API에 `Authorization: Bearer {accessToken}` 헤더가 필요합니다.

### User Service

```bash
# 구매자 회원가입
POST /api/auth/signup
{ "email": "buyer@test.com", "password": "Test1234!", "name": "구매자" }

# 판매자 회원가입
POST /api/auth/signup/seller
{ "email": "seller@test.com", "password": "Test1234!", "name": "판매자",
  "businessName": "테스트 상점", "businessNumber": "123-45-67890",
  "bankAccount": "1234567890", "bankCode": "004" }

# 로그인 → accessToken 발급
POST /api/auth/login
{ "email": "...", "password": "..." }

# 로그아웃 (토큰 블랙리스트 등록)
POST /api/auth/logout

# Telegram chatId 등록 (알림 수신)
POST /api/telegram/register
{ "chatId": 123456789 }
```

### Product Service (SELLER 전용)

```bash
# 상품 등록 (등록 시 Redis 재고 자동 초기화)
POST /api/products
{ "name": "상품명", "description": "설명", "price": 10000, "stock": 100, "category": "ELECTRONICS" }
# 카테고리: ELECTRONICS, CLOTHING, FOOD, BEAUTY, SPORTS, BOOKS, HOME, FLOWERS, ETC

# 상품 목록 조회
GET /api/products?category=ELECTRONICS

# 내 상품 목록
GET /api/products/my

# 재고 변경 (delta: 양수=증가, 음수=감소)
PATCH /api/products/{productId}/stock
{ "delta": 50 }

# 상품 비활성화 (소프트 삭제)
DELETE /api/products/{productId}
```

### Order Service

```bash
# 주문 생성 (userId는 JWT에서 추출, totalAmount는 서버 계산)
POST /api/orders
[{ "productId": "uuid", "quantity": 2 }]

# 주문 취소
POST /api/orders/{orderId}/cancel
```

### Payment Service

```bash
# PG사 결제 결과 웹훅
POST /api/payments/webhook
{ "orderId": "uuid", "result": "SUCCESS", "pgTxId": "PG-TX-123" }
```

### Settlement Service

```bash
# 내 정산 목록 (SELLER)
GET /api/settlements/my?status=PENDING

# 내 정산 요약
GET /api/settlements/my/summary

# 전체 정산 목록 (ADMIN)
GET /api/settlements?status=CONFIRMED

# 정산 지급 완료 처리 (ADMIN, CONFIRMED → PAID)
PATCH /api/settlements/{settlementId}/pay
```

---

## 수동 테스트 흐름

```bash
BASE="http://localhost:8080/api"

# 1. 판매자 회원가입 & 로그인
curl -X POST $BASE/auth/signup/seller \
  -H "Content-Type: application/json" \
  -d '{"email":"seller@test.com","password":"Test1234!","name":"판매자","businessName":"테스트상점","businessNumber":"123-45-67890","bankAccount":"1234567890","bankCode":"004"}'

SELLER_TOKEN=$(curl -s -X POST $BASE/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"seller@test.com","password":"Test1234!"}' | jq -r '.accessToken')

# 2. 상품 등록 (product-events → order-service Redis 재고 초기화)
PRODUCT_ID=$(curl -s -X POST $BASE/products \
  -H "Authorization: Bearer $SELLER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"테스트상품","description":"설명","price":10000,"stock":100,"category":"ELECTRONICS"}' \
  | jq -r '.productId')

sleep 3  # product-events 전파 대기

# 3. 구매자 회원가입 & 로그인
curl -X POST $BASE/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"buyer@test.com","password":"Test1234!","name":"구매자"}'

BUYER_TOKEN=$(curl -s -X POST $BASE/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"buyer@test.com","password":"Test1234!"}' | jq -r '.accessToken')

# 4. 주문 생성
ORDER_ID=$(curl -s -X POST $BASE/orders \
  -H "Authorization: Bearer $BUYER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1}]" | jq -r '.[0]')

# 5. 결제 완료 웹훅
curl -X POST $BASE/payments/webhook \
  -H "Content-Type: application/json" \
  -d "{\"orderId\":\"$ORDER_ID\",\"result\":\"SUCCESS\",\"pgTxId\":\"PG-TX-001\"}"

# 6. 정산 확인
curl $BASE/settlements/my \
  -H "Authorization: Bearer $SELLER_TOKEN"
```

---

## 프로젝트 구조

```
eventful-commerce/
├── common-auth/            # JWT 공통 모듈 (JwtTokenProvider, JwtAuthFilter)
├── common-outbox/          # Outbox 패턴 공통 모듈 + 이벤트 Payload 정의
├── common-idempotency/     # 멱등성 처리 공통 모듈
├── api-gateway/            # Spring Cloud Gateway MVC, JWT 검증 (8080)
├── user-service/           # 인증/인가, 회원 관리 (8085)
├── product-service/        # 상품 관리 (8086)
├── order-service/          # 주문, 재고, ProductReadModel (8081)
├── payment-service/        # 결제, PG 웹훅 (8082)
├── shipping-service/       # 배송 (8083)
├── notification-service/   # Telegram 알림 (8084)
├── settlement-service/     # 정산, Spring Batch (8087)
├── nginx/                  # Nginx 리버스 프록시 설정
├── infra/                  # Redis Cluster 초기화
├── scripts/                # DB 초기화 SQL
├── docker-compose.yml
├── build-and-push.sh       # 전체 서비스 빌드 & Docker Hub 푸시
├── pull-and-start.sh       # 이미지 Pull & 전체 기동
└── stop-all.sh
```

---

## License

MIT
