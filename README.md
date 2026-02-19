
# 🛒 Eventful Commerce

> 이벤트 기반 마이크로서비스 아키텍처로 구현한 분산 커머스 플랫폼
## 프로젝트 개요

**Eventful Commerce**는 분산 환경에서 데이터 정합성을 보장하는 이커머스 백엔드 시스템입니다.

### 핵심 목표

- **분산 트랜잭션 관리**: Saga 패턴으로 4개 서비스 간 트랜잭션 조율
- **데이터 정합성 보장**: Outbox 패턴으로 이벤트 발행 100% 보장
- **동시성 제어**: Redis Lua 스크립트 + JPA 낙관적 락으로 재고 충돌 방지
- **멱등성 보장**: 중복 이벤트 처리 방지로 안정성 확보

---

## 아키텍처

### 시스템 구성도

```
┌─────────────┐      ┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│   Client    │─────>│Order Service │─────>│Payment Service─────>│Shipping Service
│             │      │ (Port 8081)  │      │ (Port 8082)  │      │ (Port 8083)  │
└─────────────┘      └──────┬───────┘      └──────┬───────┘      └──────┬───────┘
                            │                     │                     │
                            │                     │                     │
                            v                     v                     v
                     ┌──────────────────────────────────────────────────────┐
                     │              Kafka (Event Broker)                    │
                     │                 Topic: order-events                  │
                     └──────────────────────────────────────────────────────┘
                             

┌─────────────────────────────────────────────────────────────────────────────┐
│                         Infrastructure Layer                                │
├──────────────┬──────────────────┬──────────────────┬─────────────────────┬─┤
│  PostgreSQL  │      Redis       │      Kafka       │   Docker Compose    │ │
│  (Port 5432) │   (Port 6379)    │   (Port 9092)    │                     │ │
└──────────────┴──────────────────┴──────────────────┴─────────────────────┴─┘
```

### 서비스별 책임

| 서비스 | 포트 | 책임 | 주요 기능 |
|--------|------|------|-----------|
| **Order Service** | 8081 | 주문 관리 및 재고 예약 | • 주문 생성<br>• Redis 기반 재고 예약<br>• 주문 상태 관리 (낙관적 락) |
| **Payment Service** | 8082 | 결제 처리 | • 결제 승인/실패<br>• 결제 이벤트 발행 |
| **Shipping Service** | 8083 | 배송 처리 | • 배송 시작 |

---

## 핵심 구현 사항

### 1. Saga 패턴 (Choreography)

분산 트랜잭션을 이벤트 기반으로 조율합니다.

```
[정상 플로우]
주문 생성 → 재고 예약 → 결제 요청 → 결제 승인 → 배송 시작

[보상 트랜잭션]
결제 실패 → 재고 해제 → 주문 취소
```

**구현 코드:**
```kotlin
// 주문 생성 성공 시 이벤트 발행
OutboxEvent(
    eventType = "ORDER_RESERVED",
    payload = OrderReservedPayload(orderId, reservationId, expiresAt)
)

// 결제 실패 시 보상 트랜잭션
fun handlePaymentFailed(payload: PaymentFailedPayload) {
    inventoryReservationService.release(order.reservationId)
    order.status = OrdersStatus.ORDER_CANCELED
}
```

### 2. Outbox 패턴

DB 트랜잭션과 메시지 발행을 원자적으로 처리하여 **이중 쓰기 문제**를 해결합니다.

```kotlin
@Transactional
fun orders(requests: List<OrdersRequest>) {
    // 1. 주문 저장 (DB)
    ordersRepository.saveAll(orders)
    
    // 2. Outbox 이벤트 저장 (같은 트랜잭션)
    outboxEventService.record(events)
    
    // 3. 스케줄러가 Outbox 테이블을 폴링하여 Kafka로 발행
}
```

**보장하는 것:**
- ✅ At-least-once 전달
- ✅ 트랜잭션 일관성
- ✅ 재시도 메커니즘

### 3. ⚡ 동시성 제어

#### Redis Lua 스크립트 (재고 관리)

원자적 연산으로 재고 충돌을 방지합니다.

```lua
-- reserve.lua
local stock = tonumber(redis.call('GET', KEYS[1]) or 0)
if stock > 0 then
    redis.call('DECR', KEYS[1])
    redis.call('SETEX', KEYS[2], ARGV[1], ARGV[2])  -- hold 키 생성
    redis.call('INCR', KEYS[3])  -- holdCount 증가
    return 1
else
    return 0
end
```

#### JPA 낙관적 락 (주문 상태 관리)

```kotlin
@Entity
class Orders(
    @Version
    var version: Long = 0  // 낙관적 락
) {
    var status: OrdersStatus
}

// 충돌 감지 시 OptimisticLockException 발생
```

### 4. 멱등성 보장

중복 이벤트 처리를 방지합니다.

```kotlin
@Component
class IdempotencyHandler(
    private val processedEventRepository: ProcessedEventRepository
) {
    fun <T> executeIdempotent(eventId: UUID, action: () -> T): IdempotencyResult<T> {
        return try {
            // Unique Constraint로 중복 체크
            processedEventRepository.save(ProcessedEvent(eventId))
            IdempotencyResult.Success(action())
        } catch (e: DataIntegrityViolationException) {
            IdempotencyResult.AlreadyProcessed
        }
    }
}
```

### 5. 주문 만료 처리

TTL이 지난 예약 주문을 자동으로 취소합니다.

```kotlin
@Scheduled(fixedDelay = 10000)
fun expireReservedOrders() {
    val expiredOrders = ordersRepository.findByStatusAndExpiresAtBefore(
        OrdersStatus.ORDER_RESERVED, 
        Instant.now()
    )
    
    expiredOrders.forEach { order ->
        orderExpirationService.expireOrder(order.id)  // 별도 트랜잭션
    }
}
```

---

## 기술 스택

### Backend
- **Language**: Kotlin 1.9.24
- **Framework**: Spring Boot 3.3.5
- **ORM**: Spring Data JPA (Hibernate)
- **Database**: PostgreSQL
- **Cache**: Redis
- **Message Queue**: Apache Kafka
- **Build Tool**: Gradle

### Infrastructure
- **Container**: Docker, Docker Compose
- **Logging**: Kotlin Logging

### Libraries
- **Jackson**: JSON 직렬화
- **Lettuce**: Redis 클라이언트
- **Kafka Client**: Spring Kafka

---

## 실행 방법

### 사전 요구사항

- JDK 21+
- Docker & Docker Compose
- Gradle (또는 ./gradlew 사용)

### 1. 인프라 실행

```bash
# PostgreSQL, Redis, Kafka 실행
cd infra
docker-compose up -d

# 확인
docker-compose ps
```

### 2. 데이터베이스 초기화

```bash
# 각 서비스별 DB 생성
docker exec -it infra-postgres-1 psql -U eventful -d eventful

CREATE DATABASE order_service;
CREATE DATABASE payment_service;
CREATE DATABASE shipping_service;
CREATE DATABASE notification_service;
```

### 3. 서비스 실행

```bash
# 전체 빌드
./gradlew build

# 각 서비스 실행 (별도 터미널)
./gradlew :order-service:bootRun
./gradlew :payment-service:bootRun
./gradlew :shipping-service:bootRun
./gradlew :notification-service:bootRun
```

### 4. 주문 생성 테스트

```bash
curl -X POST http://localhost:8081/orders \
  -H "Content-Type: application/json" \
  -d '[
    {
      "userId": "297A520A-F08D-4CA9-8EC9-5BFE21C0575A",
      "totalAmount": 10000
    }
  ]'
```

---

## 📊 API 문서

### Order Service (Port 8081)

#### POST /orders - 주문 생성

**Request:**
```json
[
  {
    "userId": "297A520A-F08D-4CA9-8EC9-5BFE21C0575A",
    "totalAmount": 10000
  }
]
```

**Response (성공):**
```json
  ["uuid-1", "uuid-2"]
```

**Response (부분 실패 - 재고 부족):**
```json
  ["uuid-1"]
```

### Payment Service (Port 8082)

이벤트 기반으로 동작하며 직접 API 호출 불필요

### Shipping Service (Port 8083)

이벤트 기반으로 동작하며 직접 API 호출 불필요

### Notification Service (Port 8084)

이벤트 기반으로 동작하며 직접 API 호출 불필요

---

## 프로젝트 구조

```
eventful-commerce/
├── order-service/              # 주문 서비스
│   ├── src/main/kotlin/
│   │   ├── controller/         # REST API
│   │   ├── service/           # 비즈니스 로직
│   │   ├── domain/            # 엔티티, DTO
│   │   ├── repository/        # DB 접근
│   │   ├── scheduler/         # 주문 만료 스케줄러
│   │   └── exception/         # 커스텀 예외
│   └── src/main/resources/
│       ├── lua/               # Redis Lua 스크립트
│       └── application.yml
├── payment-service/            # 결제 서비스
├── shipping-service/           # 배송 서비스
├── common-outbox/              # Outbox 패턴 공통 모듈
├── common-idempotency/         # 멱등성 처리 공통 모듈
└── infra/                      # Docker Compose 인프라
```

---

## 학습 내용

### 1. 분산 트랜잭션의 어려움

**문제**: 여러 서비스에 걸친 트랜잭션을 어떻게 관리할 것인가?

**해결**:
- ✅ Saga 패턴 (Choreography) 적용
- ✅ 보상 트랜잭션으로 롤백 처리
- ✅ 최종 일관성 (Eventual Consistency) 수용

### 2. 이중 쓰기 문제

**문제**: DB에 저장 성공 후 Kafka 발행 실패 시 데이터 불일치

**해결**:
- ✅ Outbox 패턴 적용
- ✅ DB 트랜잭션 안에서 이벤트 저장
- ✅ 스케줄러가 주기적으로 폴링하여 발행

### 3. 동시성 제어의 중요성

**문제**: 같은 재고에 대한 동시 주문 처리

**해결**:
- ✅ Redis Lua 스크립트로 원자적 연산
- ✅ JPA 낙관적 락으로 주문 상태 관리
- ✅ 스케줄러와 이벤트 핸들러 간 충돌 방지

### 4. 멱등성의 필요성

**문제**: 네트워크 오류로 같은 이벤트가 중복 발행될 수 있음

**해결**:
- ✅ ProcessedEvent 테이블로 이미 처리한 이벤트 추적
- ✅ Unique Constraint로 중복 감지
- ✅ 모든 이벤트 핸들러에 멱등성 적용
