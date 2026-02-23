# Eventful Commerce

> 이벤트 기반 분산 커머스 백엔드 - 정합성·동시성·신뢰성 중심 설계

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=spring)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7.0-DC382D?logo=redis)](https://redis.io/)
[![Kafka](https://img.shields.io/badge/Kafka-3.7-231F20?logo=apache-kafka)](https://kafka.apache.org/)

---

## 프로젝트 개요

분산 환경에서 발생하는 **재고 동시성**, **이벤트 신뢰성(double write)**, **중복 이벤트(at-least-once)** 문제를 주문-결제-배송 플로우에 적용해 **Outbox·Idempotency·Redis Lua 원자 처리**로 실패/중복/경합 상황에서도 상태가 수렴하도록 구현했습니다.

### 핵심 목표

- **분산 정합성**: 결제 실패 시 주문 취소 + 재고 해제로 상태 수렴
- **이벤트 신뢰성**: Outbox 패턴으로 DB 커밋과 이벤트 발행을 분리해 누락 방지
- **동시성 제어**: Redis Lua 스크립트로 재고 레이스 컨디션 방지
- **멱등성 보장**: 중복 이벤트 처리 방지 (at-least-once 환경)

---

## 아키텍처

### 시스템 구성도

```
┌─────────────┐      
│   Client    │
└──────┬──────┘
       │ POST /orders
       v
┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│Order Service │      │Payment Service     │Shipping Service
│ (Port 8081)  │      │ (Port 8082)  │      │ (Port 8083)  │
└──────┬───────┘      └──────┬───────┘      └──────┬───────┘
       │                     │                     │
       │ order-events        │ payment-events      │ shipping-events
       v                     v                     v
┌───────────────────────────────────────────────────────┐
│           Kafka (Topic: 서비스별 분리)                 │
└───────────────────────────────────────────────────────┘

Infrastructure Layer:
├── PostgreSQL (Port 5432) - 각 서비스 DB
├── Redis (Port 6379) - 재고 관리
└── Kafka (Port 9092) - 이벤트 브로커
```

### 서비스별 책임

| 서비스 | 포트 | 책임 | 주요 기능 |
|--------|------|------|-----------|
| **Order Service** | 8081 | 주문 관리 및 재고 예약 | • 주문 생성 (All or Nothing)<br>• Redis 기반 재고 예약/확정/해제<br>• 주문 상태 관리 (낙관적 락) |
| **Payment Service** | 8082 | 결제 처리 | • 결제 승인/실패<br>• Webhook API 제공<br>• 결제 이벤트 발행 |
| **Shipping Service** | 8083 | 배송 처리 | • 배송 생성<br>• 배송 이벤트 발행 |

---

## 핵심 구현 사항

### 1. 이벤트 기반 분산 플로우 + 보상 로직

주문-결제-배송 흐름을 이벤트로 분리하고, 실패 시 **보상 로직**으로 상태 수렴

```
[정상 플로우]
주문 생성 → 재고 예약 → 결제 요청 → 결제 승인 → 배송 준비

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
- ✅ DB 커밋된 이벤트의 최종 발행 가능성 (재시도)
- ✅ 트랜잭션 일관성
- ✅ At-least-once 전달

### 3. 동시성 제어

#### Redis Lua 스크립트 (재고 관리)

원자적 연산으로 재고 충돌을 방지합니다.

```lua
-- reserve.lua: 재고 예약
local stock = tonumber(redis.call('GET', KEYS[1]) or 0)
if stock > 0 then
    redis.call('DECR', KEYS[1])  -- 재고 감소
    redis.call('SETEX', KEYS[2], ARGV[1], ARGV[2])  -- hold 키 생성 (TTL)
    redis.call('INCR', KEYS[3])  -- holdCount 증가
    return 1
else
    return 0
end
```

**3가지 연산:**
- `reserve`: 재고 감소 + reservation 키 생성 (TTL 10분)
- `commit`: 결제 성공 시 확정
- `release`: 결제 실패/만료 시 재고 복구

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

중복 이벤트 처리를 방지합니다 (Kafka at-least-once 전제).

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
            IdempotencyResult.AlreadyProcessed  // 이미 처리됨
        }
    }
}
```

### 5. 주문 만료 처리

TTL이 지난 예약 주문을 자동으로 취소하고 재고를 복구합니다.

```kotlin
@Scheduled(fixedDelay = 10000)  // 10초마다 실행
fun expireReservedOrders() {
    val expiredOrders = ordersRepository.findByStatusAndExpiresAtBefore(
        OrdersStatus.ORDER_RESERVED, 
        Instant.now()
    )
    
    expiredOrders.forEach { order ->
        orderExpirationService.expireOrder(order.id)
    }
}
```

---

## 기술 스택

### Backend
- **Language**: Kotlin 1.9.24
- **Framework**: Spring Boot 3.3.5
- **ORM**: Spring Data JPA (Hibernate)
- **Database**: PostgreSQL 16
- **Cache**: Redis 7.0
- **Message Queue**: Apache Kafka 3.7.0
- **Build Tool**: Gradle 8.x

### Infrastructure
- **Container**: Docker, Docker Compose
- **Logging**: Kotlin Logging

---

## Quick Start

### 사전 요구사항

- JDK 17+
- Docker & Docker Compose

### 1. 인프라 실행

```bash
# PostgreSQL, Redis, Kafka 실행
docker-compose up -d

# 확인
docker ps
```

### 2. 서비스 실행

```bash
# 전체 빌드
./gradlew build

# 각 서비스 실행 (별도 터미널)
./gradlew :order-service:bootRun
./gradlew :payment-service:bootRun
./gradlew :shipping-service:bootRun
```

### 3. 주문 생성 테스트

```bash
# 주문 생성
curl -X POST http://localhost:8081/orders \
  -H "Content-Type: application/json" \
  -d '[
    {
      "userId": "297A520A-F08D-4CA9-8EC9-5BFE21C0575A",
      "totalAmount": 10000
    }
  ]'

# 성공 시 응답: ["<order-uuid>"]
```

### 4. 결제 처리 (Webhook)

```bash
# 결제 성공
curl -X POST http://localhost:8082/payments/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "<order-uuid>",
    "result": "SUCCESS",
    "pgTxId": "PG-TX-123"
  }'

# 결제 실패
curl -X POST http://localhost:8082/payments/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "<order-uuid>",
    "result": "FAILURE"
  }'
```

---

## API 문서

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

**Response 200 OK (전체 성공):**
```json
["uuid-1", "uuid-2"]
```

**Response 409 CONFLICT (재고 부족):**
```json
{
  "code": "INSUFFICIENT_INVENTORY",
  "message": "재고 부족으로 전체 주문이 취소되었습니다. 실패 주문: uuid-1",
  "details": "uuid-1",
  "timestamp": "2024-01-01T00:00:00Z"
}
```

> **All or Nothing 정책**: 여러 주문 중 하나라도 실패하면 전체 실패 (부분 성공 없음)

---

### Payment Service (Port 8082)

#### POST /payments/webhook - 결제 결과 수신

**Request:**
```json
{
  "orderId": "uuid",
  "result": "SUCCESS",  // or "FAILURE"
  "pgTxId": "PG-TX-123",
  "amount": 10000
}
```

**Response:**
```json
"Payment successful"
```

---

### Shipping Service (Port 8083)

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
│   │   ├── message/           # Kafka 컨슈머
│   │   └── exception/         # 예외 처리
│   └── src/main/resources/
│       ├── lua/               # Redis Lua 스크립트
│       └── application.yml
├── payment-service/            # 결제 서비스
├── shipping-service/           # 배송 서비스
├── common-outbox/              # Outbox 패턴 공통 모듈
├── common-idempotency/         # 멱등성 처리 공통 모듈
├── docker-compose.yml          # 인프라 설정
└── scripts/
    └── init-databases.sql      # DB 초기화
```

---

## Troubleshooting

### 1. Outbox 중복 발행과 컨슈머 멱등성의 갭

**문제**: Outbox 재시도로 동일 이벤트가 2번 발행되었는데, 첫 번째 처리는 성공했지만 DB 커밋 전 장애로 `processed_event` 테이블에 기록 안 됨

**시나리오**:
```
1. Outbox Publisher: 이벤트 A 발행 → Kafka 성공
2. Consumer: 이벤트 A 수신 → 비즈니스 로직 실행 → DB 커밋 전 서버 다운
3. Consumer 재시작 → processed_event에 A 없음
4. Outbox Publisher: 이벤트 A 재발행 (SENT 아님)
5. Consumer: 이벤트 A를 "새 이벤트"로 인식 → 중복 처리!
```

**원인**:
- Outbox는 "발행 완료" 여부만 체크 (Kafka 응답)
- Consumer는 "처리 완료" 여부를 DB 커밋으로 판단
- 사이에 **장애 발생 시점의 갭** 존재

**해결**:
```kotlin
    // 1. 먼저 processed_event 저장 (멱등성 체크)
    return try {
        // 멱등성 체크: 이미 처리된 이벤트인지 확인
        processedEventRepository.save(ProcessedEvent(eventId))

        // 처음 처리하는 이벤트이므로 비즈니스 로직 실행
        val result = action()
        logger.debug("이벤트 처리 완료: eventId={}", eventId)
        IdempotencyResult.Success(result)

    } catch (e: DataIntegrityViolationException) {
        // 중복 키 제약 위반 = 이미 처리된 이벤트
        logger.debug("이미 처리된 이벤트: eventId={}", eventId)
        IdempotencyResult.AlreadyProcessed

    } catch (e: Exception) {
        // 기타 예외는 상위로 전파
        logger.error("이벤트 처리 중 오류 발생: eventId={}", eventId, e)
        throw e
    }
    
    // 2. 비즈니스 로직 실행
    val result = action()
    
    // ... 나머지 처리
    
    // 3. 모두 같은 트랜잭션에서 커밋

```

**핵심**: 멱등성 체크를 비즈니스 로직 **앞**에 배치해 트랜잭션 경계 일치

---

### 2. Redis TTL 만료와 스케줄러 동시 처리 (낙관적 락 충돌)

**문제**: 주문 만료 처리 중 결제 완료 이벤트가 동시에 도착해 충돌 발생

**시나리오**:
```
1. 주문 A: expiresAt = 10:00:00, 현재 10:00:01
2. Scheduler: 만료된 주문 A 발견 → 취소 처리 시작
3. 동시에 Payment Complete 이벤트 도착 → 확정 처리 시작
4. Scheduler: UPDATE orders SET status=CANCELED, version=version+1
5. Payment Handler: UPDATE orders SET status=CONFIRMED, version=version+1
6. → OptimisticLockException 발생!
```

**원인**:
- TTL 만료와 결제 완료가 **거의 동시** 발생 가능
- 두 트랜잭션이 동일 주문을 업데이트 시도
- **낙관적 락 충돌**

**해결**:
```kotlin
// OrderExpirationService
@Transactional
fun expireOrder(orderId: UUID) {
    val order = ordersRepository.findById(orderId).orElse(null) ?: run {
        logger.warn { "주문을 찾을 수 없음: orderId=$orderId" }
        return
    }

    // 낙관적 락을 활용한 동시성 제어
    // 이미 다른 트랜잭션에서 상태가 변경되었다면 OptimisticLockException 발생
    if (order.status != OrdersStatus.ORDER_RESERVED) {
        logger.info { "이미 처리된 주문: orderId=$orderId, status=${order.status}" }
        return
    }

    val reservationId = order.reservationId
    if (reservationId != null) {
        logger.info { "만료된 주문 재고 해제: orderId=$orderId, reservationId=$reservationId" }
        inventoryReservationService.release(reservationId)
    } else {
        logger.warn { "예약 ID가 없는 만료 주문: orderId=$orderId" }
    }

    order.status = OrdersStatus.ORDER_EXPIRED
    ordersRepository.save(order) // @Version으로 낙관적 락 체크

    logger.info { "주문 만료 처리 완료: orderId=$orderId" }
}
```

**핵심**:
- 낙관적 락 예외를 "정상 케이스"로 처리
- Redis reservation 존재 여부로 2차 검증

---

### 3. At-least-once로 인한 재고 중복 차감 시도

**문제**: 동일한 `ORDER_RESERVED` 이벤트가 재처리되어 Payment가 2번 생성됨

**시나리오**:
```
1. ORDER_RESERVED 이벤트 수신 → Payment 생성 (PENDING)
2. Kafka 커밋 전 Consumer 재시작
3. 동일 이벤트 재수신 → Payment 또 생성?
```

**원인**: Payment Service에 멱등성 미적용

**해결**:
```kotlin
// PaymentService
@Transactional
fun handleOrderReserved(eventMessage: OutboxEventMessage) {
    // 1. 멱등성 체크
    idempotencyHandler.executeIdempotent(eventMessage.eventId) {
        val payload = objectMapper.readValue(eventMessage.payload, OrderReservedPayload::class.java)
        
        // 2. 이미 Payment 존재하면 스킵
        val existing = paymentRepository.findByOrderId(payload.orderId)
        if (existing != null) {
            logger.info { "이미 결제 존재: ${existing.id}" }
            return@executeIdempotent
        }
        
        // 3. Payment 생성
        val payment = Payment(
            orderId = payload.orderId,
            amount = payload.totalAmount,
            status = PaymentStatus.PENDING
        )
        paymentRepository.save(payment)
    }
}
```

**핵심**:
- Idempotency Handler로 중복 실행 방지
- 비즈니스 로직 내에도 중복 생성 체크

---

## 학습 내용

### 1. 분산 트랜잭션 관리

**문제**: 여러 서비스에 걸친 트랜잭션을 어떻게 관리할 것인가?

**해결**:
- ✅ 이벤트 기반 플로우로 서비스 간 결합도 감소
- ✅ 보상 트랜잭션으로 실패 시 상태 수렴
- ✅ 최종 일관성 (Eventual Consistency) 수용

### 2. 이중 쓰기 문제

**문제**: DB 커밋 성공 후 Kafka 발행 실패 시 데이터 불일치

**해결**:
- ✅ Outbox 패턴으로 DB 트랜잭션 내 이벤트 저장
- ✅ 스케줄러가 폴링 후 발행 (재시도 가능)
- ✅ 발행 실패에도 누락 없음

### 3. 동시성 제어

**문제**: 한정 재고에 동시 주문 시 레이스 컨디션

**해결**:
- ✅ Redis Lua 스크립트로 원자적 연산
- ✅ JPA 낙관적 락으로 주문 상태 경합 방지
- ✅ 스케줄러와 이벤트 핸들러 간 충돌 감지

### 4. 멱등성의 필요성

**문제**: Kafka at-least-once로 중복 이벤트 발생

**해결**:
- ✅ eventId 기반 중복 감지
- ✅ Unique Constraint로 자동 차단
- ✅ 모든 컨슈머에 멱등성 적용
