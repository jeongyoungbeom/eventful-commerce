# Eventful Commerce

이벤트 기반 마이크로서비스 아키텍처로 구현한 분산 커머스 백엔드

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=spring)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis%20Cluster-7.0-DC382D?logo=redis)](https://redis.io/)
[![Kafka](https://img.shields.io/badge/Kafka-3.7-231F20?logo=apache-kafka)](https://kafka.apache.org/)

---

## 프로젝트 개요

주문-결제-배송 플로우를 이벤트로 연결한 분산 시스템입니다.

**핵심 구현:**
- Outbox 패턴으로 이벤트 발행 신뢰성 보장
- Redisson 분산락으로 주문 취소 동시성 제어
- Redis Lua 스크립트로 재고 원자적 처리
- Idempotency 패턴으로 중복 이벤트 방지

---

## 아키텍처

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ POST /orders
       v
┌──────────────────────────────────────────────────┐
│            Application Layer                     │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────┐│
│  │Order Service │  │Payment Service  │Shipping ││
│  │  (8081)      │  │  (8082)      │  │ (8083)  ││
│  └──────┬───────┘  └──────┬───────┘  └────┬────┘│
└─────────┼──────────────────┼───────────────┼─────┘
          │                  │               │
          └────────┬─────────┴───────┬───────┘
                   v                 v
          ┌─────────────────────────────────┐
          │   Kafka (이벤트 브로커)          │
          └─────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│            Infrastructure Layer                  │
│  ┌──────────────┐  ┌───────────────────────────┐│
│  │PostgreSQL    │  │Redis Cluster (6 nodes)    ││
│  │(각 서비스 DB)│  │ • 재고 관리 (Lua 스크립트) ││
│  │              │  │ • 분산락 (Redisson)       ││
│  └──────────────┘  └───────────────────────────┘│
└──────────────────────────────────────────────────┘
```

**이벤트 플로우:**
```
주문 생성 → ORDER_RESERVED 이벤트 발행
         → Payment Service 수신 → 결제 처리
         → PAYMENT_COMPLETED 이벤트 발행
         → Shipping Service 수신 → 배송 생성
```

---

## 기술 스택

- **Language**: Kotlin 1.9.24
- **Framework**: Spring Boot 3.3.5
- **Database**: PostgreSQL 16
- **Cache**: Redis Cluster 7.0 (6 nodes)
- **Distributed Lock**: Redisson 3.27.2
- **Message Queue**: Apache Kafka 3.7.0
- **Container**: Docker, Docker Compose

---

## 핵심 기능

### 1. Outbox 패턴

DB 트랜잭션과 이벤트 발행을 원자적으로 처리합니다.

```kotlin
@Transactional
fun createOrder(request: OrderRequest) {
    // 1. 주문 저장
    ordersRepository.save(order)
    
    // 2. Outbox에 이벤트 저장 (같은 트랜잭션)
    outboxEventService.record(OrderReservedEvent)
    
    // 3. 스케줄러가 Outbox 폴링 → Kafka 발행
}
```

### 2. Redis Lua 스크립트 (재고 관리)

원자적 연산으로 재고 경합을 방지합니다.

```lua
-- reserve.lua
local stock = redis.call('GET', KEYS[1])
if stock > 0 then
    redis.call('DECR', KEYS[1])        -- 재고 감소
    redis.call('SETEX', KEYS[2], ...)  -- 예약 생성 (TTL)
    return 1
end
return 0
```

**3가지 연산:**
- `reserve`: 재고 감소 + 예약 생성 (TTL 10분)
- `commit`: 결제 성공 시 확정
- `release`: 결제 실패/만료 시 재고 복구

### 3. 분산락 (Redisson)

주문 취소의 동시성 문제를 분산락으로 해결합니다.

**동시성 시나리오:**
- 사용자 중복 클릭
- 자동 만료 vs 수동 취소 동시 발생
- 결제 완료 vs 취소 요청 충돌

**구현:**
```kotlin
// 락 관리 (OrderCancelService)
fun cancel(orderId: UUID): Boolean {
    val lock = redissonClient.getLock("order:lock:$orderId")
    lock.tryLock(10, 30, TimeUnit.SECONDS)
    try {
        return orderCancelExecutor.execute(orderId)  // 트랜잭션 분리
    } finally {
        lock.unlock()
    }
}

// 트랜잭션 처리 (OrderCancelExecutor)
@Transactional
fun execute(orderId: UUID): Boolean {
    // 재고 해제 + 주문 취소
}
```

### 4. Idempotency (멱등성)

Kafka at-least-once 환경에서 중복 이벤트를 방지합니다.

```kotlin
fun handleEvent(eventId: UUID) {
    processedEventRepository.save(ProcessedEvent(eventId))  // Unique Constraint
    // 비즈니스 로직 실행
}
```

### 5. 주문 만료 처리

TTL 초과 주문을 자동 취소하고 재고를 복구합니다.

```kotlin
@Scheduled(fixedDelay = 10000)
fun expireOrders() {
    val expired = ordersRepository.findExpired(Instant.now())
    expired.forEach { orderCancelService.cancel(it.id, "만료") }
}
```

---

## Quick Start

### 실행 방법

```bash
# 1. 최신 이미지 Pull & 전체 시스템 실행
./pull-and-start.sh

# 2. 재고 초기화
./scripts/init-redis-cluster-stock.sh

# 3. 서비스 확인
curl http://localhost:8081/actuator/health  # Order
curl http://localhost:8082/actuator/health  # Payment
curl http://localhost:8083/actuator/health  # Shipping
```

### 종료

```bash
./stop-all.sh
```

---

## API 문서

### Order Service (8081)

#### POST /orders - 주문 생성

```bash
curl -X POST http://localhost:8081/orders \
  -H "Content-Type: application/json" \
  -d '[{
    "userId": "user-001",
    "productId": "PRODUCT-001",
    "quantity": 1,
    "totalAmount": 10000
  }]'
```

**Response:**
```json
["uuid-1", "uuid-2"]
```

#### POST /orders/{orderId}/cancel - 주문 취소

```bash
curl -X POST http://localhost:8081/orders/{orderId}/cancel
```

**Response:**
```json
{
  "success": true,
  "orderId": "uuid",
  "message": "주문이 취소되었습니다"
}
```

### Payment Service (8082)

#### POST /payments/webhook - 결제 결과 수신

```bash
curl -X POST http://localhost:8082/payments/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "uuid",
    "result": "SUCCESS",
    "pgTxId": "PG-TX-123"
  }'
```

---

## 테스트

### 자동화 테스트

```bash
# 전체 테스트 (빌드 포함)
./scripts/test-order-cancel-all.sh

# 개별 테스트
./scripts/test-order-cancel-single.sh      # 단일 취소
./scripts/test-order-cancel-concurrent.sh  # 동시성 (2개)
./scripts/test-order-cancel-stress.sh      # 스트레스 (10개)
```

### 수동 테스트

```bash
# 1. 주문 생성
ORDER_ID=$(curl -s -X POST http://localhost:8081/orders \
  -H "Content-Type: application/json" \
  -d '[{"userId": "user-001", "productId": "PRODUCT-001", "quantity": 1, "totalAmount": 10000}]' \
  | jq -r '.[0]')

# 2. 주문 취소
curl -X POST http://localhost:8081/orders/$ORDER_ID/cancel

# 3. 동시 취소 테스트 (중복 클릭 시뮬레이션)
curl -X POST http://localhost:8081/orders/$ORDER_ID/cancel &
curl -X POST http://localhost:8081/orders/$ORDER_ID/cancel &
wait
```

**예상 결과:** 하나만 성공, 나머지는 실패

---

## 프로젝트 구조

```
eventful-commerce/
├── order-service/
│   ├── src/main/kotlin/
│   │   ├── controller/
│   │   ├── service/
│   │   │   ├── OrderCancelService.kt      # 분산락 관리
│   │   │   ├── OrderCancelExecutor.kt     # 트랜잭션 처리
│   │   │   └── ...
│   │   ├── config/
│   │   │   └── RedissonConfig.kt          # Redisson 설정
│   │   ├── domain/
│   │   ├── repository/
│   │   └── scheduler/
│   └── src/main/resources/
│       ├── lua/                            # Redis Lua 스크립트
│       └── application.yml
├── payment-service/
├── shipping-service/
├── common-outbox/                          # Outbox 공통 모듈
├── common-idempotency/                     # 멱등성 공통 모듈
├── docker-compose.yml
└── scripts/
    ├── init-databases.sql
    ├── init-redis-cluster-stock.sh
    ├── test-order-cancel-single.sh
    ├── test-order-cancel-concurrent.sh
    ├── test-order-cancel-stress.sh
    └── test-order-cancel-all.sh
```

---

## License

MIT
