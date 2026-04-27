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
- ✨ **Telegram Bot 실시간 알림** (Week 3)

**최근 업데이트:**
- 🆕 **Week 3 Day 1** (2026-04-27): Notification Service 구축
  - Telegram Bot 통합으로 실시간 알림 전송
  - 5가지 이벤트 타입 처리 (주문/결제/배송/취소)
  - Shipping Service 자동 배송 완료 (@Async, 10초 지연)
  - 타입 일관성 개선 (전체 userId UUID 통일)

---

## 아키텍처

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ POST /orders
       v
┌──────────────────────────────────────────────────────────────┐
│                    Application Layer                         │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────┐ ┌────────┐│
│  │Order Service │  │Payment Service  │Shipping ││Notif.  ││
│  │  (8081)      │  │  (8082)      │  │ (8083)  ││(8084)  ││
│  └──────┬───────┘  └──────┬───────┘  └────┬────┘└────┬───┘│
└─────────┼──────────────────┼───────────────┼──────────┼─────┘
          │                  │               │          │
          └────────┬─────────┴───────┬───────┴──────────┘
                   v                 v
          ┌─────────────────────────────────┐
          │   Kafka (이벤트 브로커)          │
          │  • order-events                 │
          │  • payment-events               │
          │  • shipping-events              │
          └─────────────────────────────────┘
                   │
                   v
          ┌─────────────────────────────────┐
          │   Telegram Bot API              │
          │   (실시간 알림 전송)              │
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
         → SHIPPING_STARTED 이벤트 발행
         → 10초 후 자동으로 SHIPPING_COMPLETED 발행
         
각 이벤트 → Notification Service 수신
         → DB 저장 + Telegram 실시간 알림 전송
```

---

## 기술 스택

- **Language**: Kotlin 1.9.24
- **Framework**: Spring Boot 3.3.5
- **Database**: PostgreSQL 16
- **Cache**: Redis Cluster 7.0 (6 nodes)
- **Distributed Lock**: Redisson 3.27.2
- **Message Queue**: Apache Kafka 3.7.0
- **Notification**: Telegram Bot API
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

### 6. 실시간 알림 (Telegram Bot)

주문 프로세스의 각 단계를 Telegram으로 실시간 알림합니다.

**알림 타입:**
- `ORDER_RESERVED`: 주문 접수 완료
- `ORDER_CANCELED`: 주문 취소
- `PAYMENT_COMPLETED`: 결제 완료
- `SHIPPING_STARTED`: 배송 시작
- `SHIPPING_COMPLETED`: 배송 완료

**구현:**
```kotlin
@Service
class NotificationService(
    private val telegramService: TelegramService
) {
    @Transactional
    fun createAndSend(userId: UUID, type: NotificationType, message: String) {
        // 1. 텔레그램 전송 먼저 시도
        val telegramMessageId = telegramService.sendNotification(userId, message)
        
        // 2. 결과와 함께 한 번에 DB 저장
        val notification = Notification(
            userId = userId,
            type = type,
            message = message,
            sentToTelegram = telegramMessageId != null,
            telegramMessageId = telegramMessageId
        )
        
        return notificationRepository.save(notification)
    }
}
```

**Kafka Consumer:**
```kotlin
@KafkaListener(topics = ["order-events"])
fun consumeOrderEvents(message: String) {
    val event = objectMapper.readValue<OutboxEventMessage>(message)
    
    when (event.eventType) {
        "ORDER_RESERVED" -> {
            val payload = objectMapper.readValue<OrderReservedPayload>(event.payload)
            notificationService.createAndSend(
                userId = payload.userId,
                type = NotificationType.ORDER_RESERVED,
                message = "주문이 접수되었습니다"
            )
        }
        // ... 다른 이벤트 처리
    }
}
```

---

## Quick Start

### 사전 준비 (Telegram Bot 설정)

알림 기능을 사용하려면 Telegram Bot 설정이 필요합니다.

```bash
# 1. BotFather에서 봇 생성
# Telegram에서 @BotFather 검색 → /newbot 명령

# 2. 봇 토큰 환경변수 설정
export TELEGRAM_BOT_TOKEN="your-bot-token-here"

# 3. 봇에게 메시지 전송 후 Chat ID 확인
curl https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getUpdates

# 4. Chat ID를 환경변수로 설정 (선택)
export TEST_CHAT_ID="your-chat-id-here"
```

### 실행 방법

```bash
# 1. 최신 이미지 Pull & 전체 시스템 실행
./pull-and-start.sh

# 2. 재고 초기화
./scripts/init-redis-cluster-stock.sh

# 3. 텔레그램 사용자 등록 (알림 기능 사용 시)
./scripts/init-telegram-users.sh

# 4. 서비스 확인
curl http://localhost:8081/actuator/health  # Order
curl http://localhost:8082/actuator/health  # Payment
curl http://localhost:8083/actuator/health  # Shipping
curl http://localhost:8084/actuator/health  # Notification
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

### 주문 취소 테스트

```bash
# 전체 테스트 (빌드 포함)
./scripts/test-order-cancel-all.sh

# 개별 테스트
./scripts/test-order-cancel-single.sh      # 단일 취소
./scripts/test-order-cancel-concurrent.sh  # 동시성 (2개)
./scripts/test-order-cancel-stress.sh      # 스트레스 (10개)
```

### 알림 플로우 테스트

전체 주문-결제-배송-알림 플로우를 통합 테스트합니다.

```bash
# 1. 텔레그램 사용자 등록
./scripts/init-telegram-users.sh

# 2. 전체 플로우 테스트
./scripts/test-notification-flow.sh
```

**테스트 시나리오:**
1. 주문 생성 → `ORDER_RESERVED` 알림
2. 결제 완료 → `PAYMENT_COMPLETED` 알림
3. 배송 시작 → `SHIPPING_STARTED` 알림
4. 배송 완료 (10초 후) → `SHIPPING_COMPLETED` 알림

**예상 결과:**
```
📊 생성된 알림 목록:
        type        |     title      | sent | read |   time
--------------------+----------------+------+------+----------
 ORDER_RESERVED     | 주문 접수 완료 | t    | f    | 14:53:57
 PAYMENT_COMPLETED  | 결제 완료      | t    | f    | 14:54:19
 SHIPPING_STARTED   | 배송 시작      | t    | f    | 14:54:29
 SHIPPING_COMPLETED | 배송 완료      | t    | f    | 14:54:40

📈 알림 통계:
  전체: 4개
  전송: 4개
  ✅ 모든 알림 전송 성공!
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
│   └── src/main/kotlin/
│       └── service/
│           └── ShippingService.kt         # @Async 배송 완료 처리
├── notification-service/                  
│   ├── src/main/kotlin/
│   │   ├── controller/
│   │   │   └── NotificationController.kt  # 알림 조회 API
│   │   ├── service/
│   │   │   ├── NotificationService.kt     # 알림 생성 및 전송
│   │   │   ├── TelegramService.kt         # Telegram Bot API
│   │   │   └── NotificationTemplate.kt    # 메시지 템플릿
│   │   ├── message/                       # Kafka Consumer
│   │   │   ├── OrderEventsConsumer.kt
│   │   │   ├── PaymentEventsConsumer.kt
│   │   │   └── ShippingEventsConsumer.kt
│   │   ├── domain/
│   │   │   ├── entity/
│   │   │   │   ├── Notification.kt
│   │   │   │   └── UserChatId.kt
│   │   │   └── NotificationType.kt
│   │   └── repository/
│   └── src/main/resources/
│       └── application.yml
├── common-outbox/                          # Outbox 공통 모듈
│   └── src/main/kotlin/
│       ├── OrderCanceledPayload.kt        
│       ├── ShippingStartedPayload.kt      
│       └── ShippingCompletedPayload.kt    
├── common-idempotency/                     # 멱등성 공통 모듈
├── docker-compose.yml
└── scripts/
    ├── init-databases.sql
    ├── init-redis-cluster-stock.sh
    ├── init-telegram-users.sh             
    ├── test-notification-flow.sh         
    ├── test-order-cancel-single.sh
    ├── test-order-cancel-concurrent.sh
    ├── test-order-cancel-stress.sh
    └── test-order-cancel-all.sh
```

---

## License

MIT
