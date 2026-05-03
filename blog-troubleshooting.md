# 마이크로서비스 개발하면서 겪은 트러블슈팅 7가지

Spring Boot + Kotlin + Kafka + Redis Cluster + Docker 조합으로 마이크로서비스를 개발하면서 겪었던 삽질들을 정리했습니다.

정산 시스템, 상품 관리, Kafka 이벤트 기반 아키텍처를 구현하는 과정에서 나온 이슈들이라 비슷한 스택을 쓰는 분들에게 도움이 될 것 같습니다.

---

## 1. kotlin-reflect 누락 — Spring Data JPA 빈 생성 실패

### 에러 메시지

```
java.lang.ClassNotFoundException: kotlin.reflect.full.KClasses
Error creating bean with name 'sellerRepository' defined in 
com.eventfulcommerce.user.domain.repository.SellerRepository
```

### 상황

멀티모듈 프로젝트에서 user-service는 잘 기동되다가 어느 순간 갑자기 위 에러가 발생했습니다. payment-service, order-service, shipping-service는 전혀 문제가 없었는데 유독 user-service만 터졌습니다.

### 원인 분석

Spring Data JPA는 엔티티 클래스의 생성자를 분석해서 객체를 어떻게 만들지 결정합니다. Java 클래스라면 `java.lang.reflect`만으로 충분하지만, **Kotlin 클래스는 `kotlin-reflect`가 런타임에 별도로 필요합니다.**

Kotlin의 data class는 primary constructor, default parameter, nullable 타입 등 Java 리플렉션으로는 알 수 없는 메타데이터를 갖고 있기 때문입니다.

그렇다면 다른 서비스는 왜 문제가 없었을까요? 확인해보니 **우연히 transitive dependency로 해결**되고 있었습니다.

| 서비스 | kotlin-reflect를 끌어오는 경로 |
|---|---|
| order-service | `redisson-spring-boot-starter` → `kotlin-reflect` 포함 |
| payment-service | `common-outbox` → `jackson-module-kotlin` → `kotlin-reflect` 포함 |
| shipping-service | `common-outbox` → `jackson-module-kotlin` → `kotlin-reflect` 포함 |
| **user-service** | `common-auth`만 의존 → `kotlin-reflect` **없음** → 빈 생성 실패 |

user-service만 `common-outbox`를 사용하지 않아서 이 구멍이 노출된 겁니다.

### 해결

root `build.gradle.kts`의 `subprojects` 블록에 명시적으로 추가해서 모든 모듈이 확실히 보장받도록 했습니다.

```kotlin
// build.gradle.kts (root)
subprojects {
    dependencies {
        "implementation"("org.jetbrains.kotlin:kotlin-reflect")
        "testImplementation"("org.jetbrains.kotlin:kotlin-test")
    }
}
```

Spring Initializr로 프로젝트를 생성하면 `kotlin-reflect`가 항상 명시적으로 추가되는 이유가 바로 이것입니다. 멀티모듈 프로젝트에서 모듈 간 의존성이 달라지면 이런 숨은 의존성 문제가 생기기 쉽습니다.

---

## 2. Redis Cluster 재시작 시 "not empty" 에러

### 에러 메시지

```
[ERR] Node redis-node-1:7001 is not empty. 
Either the node already knows other nodes (check with CLUSTER NODES) 
or contains some key in database 0.
```

### 상황

개발 중에 `docker compose down` 후 다시 `docker compose up`을 하면 Redis 클러스터 초기화 스크립트가 실패하는 문제였습니다. 처음 올릴 때는 잘 되는데, 한 번이라도 클러스터가 구성된 이후부터 계속 터졌습니다.

### 원인 분석

`docker compose down`은 컨테이너를 내리지만, volume은 유지됩니다. 따라서 Redis 노드들은 이전 클러스터 구성 정보를 그대로 갖고 있습니다.

초기화 스크립트는 매번 `--cluster create`를 실행했는데, 이미 클러스터 멤버인 노드에 다시 `create`를 시도하면 Redis가 "이미 데이터/설정이 있다"며 거부하는 겁니다.

```bash
# 문제가 있는 스크립트 — 매번 무조건 create 시도
docker exec -i redis-node-1 redis-cli --cluster create \
  redis-node-1:7001 \
  redis-node-2:7002 \
  ...
  --cluster-replicas 1 \
  --cluster-yes
```

### 해결

클러스터 초기화 전에 먼저 상태를 확인하고, 이미 정상 구성된 경우 건너뜁니다.

```bash
#!/bin/bash

echo "Waiting for Redis nodes to be ready..."
sleep 10

# 클러스터가 이미 구성되어 있는지 확인
CLUSTER_STATE=$(docker exec redis-node-1 redis-cli -p 7001 cluster info 2>/dev/null \
  | grep "cluster_state" \
  | tr -d '\r')

if [ "$CLUSTER_STATE" = "cluster_state:ok" ]; then
  echo "Redis Cluster already exists, skipping creation."
  docker exec redis-node-1 redis-cli -p 7001 cluster nodes
  exit 0
fi

echo "Creating Redis Cluster..."
docker exec -i redis-node-1 redis-cli --cluster create \
  redis-node-1:7001 \
  redis-node-2:7002 \
  redis-node-3:7003 \
  redis-node-4:7004 \
  redis-node-5:7005 \
  redis-node-6:7006 \
  --cluster-replicas 1 \
  --cluster-yes
```

`cluster info` 명령으로 `cluster_state:ok` 여부를 먼저 체크해서 멱등성을 확보했습니다.

---

## 3. Redisson 기동 시 "Not all slots covered" 에러

### 에러 메시지

```
org.redisson.client.RedisConnectionException: Not all slots covered! 
Only 10923 slots are available. 
Set checkSlotsCoverage = false to avoid this check.
```

### 상황

order-service가 뜨다가 위 에러로 기동에 실패했습니다. Redis 클러스터는 6개 노드(마스터 3 + 레플리카 3)로 구성했는데, 전체 16384개 슬롯 중 10923개만 커버된 상태라고 합니다.

### 원인 분석

Redis 클러스터 초기화(`init-redis-cluster.sh`)에는 `sleep 10`이 있어서 10초 후에 클러스터를 구성합니다. 그런데 order-service는 그 전에 먼저 기동을 시작해버려서, **클러스터가 완전히 구성되기 전 시점에 Redisson이 연결을 시도**한 겁니다.

Redis 클러스터는 3개 마스터가 16384개 슬롯을 나눠 갖는데, 초기화 도중에는 일부 마스터만 슬롯을 받은 중간 상태가 존재합니다. Redisson 기본 설정은 이 상태를 허용하지 않고 예외를 던집니다.

```kotlin
// 기존 설정 — 모든 슬롯이 커버되어야만 연결 허용
clusterServersConfig
    .setScanInterval(2000)
    .setConnectTimeout(10000)
    .setTimeout(3000)
    .setRetryAttempts(3)
    .setRetryInterval(1500)
// → 슬롯이 부족하면 예외 발생 후 서비스 기동 실패
```

### 해결

`setCheckSlotsCoverage(false)`를 추가해서 기동 시점의 슬롯 커버리지 검사를 비활성화합니다. 이후 Redisson의 재시도 로직과 Docker의 `restart: on-failure` 정책이 맞물려 자연스럽게 연결됩니다.

```kotlin
clusterServersConfig
    .setScanInterval(2000)
    .setConnectTimeout(10000)
    .setTimeout(3000)
    .setRetryAttempts(3)
    .setRetryInterval(1500)
    .setCheckSlotsCoverage(false) // 기동 시점 슬롯 커버리지 검사 비활성화
```

docker-compose에 `restart: on-failure`가 설정되어 있다면, 첫 시도에 실패해도 클러스터가 완전히 구성된 후 재기동되면서 정상 연결됩니다.

---

## 4. Docker network "needs to be recreated" 에러

### 에러 메시지

```
ERROR: Network 'myproject_app-network' needs to be recreated
```

### 상황

`docker compose up` 할 때마다 위 에러가 발생했습니다. `docker compose down`을 해도 해결이 안 됐고, 매번 `docker network rm`으로 네트워크를 지워야 했습니다.

### 원인 분석

Docker 신버전에서 브리지 네트워크에 `enable_ipv4` 옵션이 추가되었습니다. 기존에 생성된 네트워크에는 이 옵션 정보가 없는데, 현재 Docker 버전이 이 옵션을 기본값으로 적용하려 하면서 저장된 네트워크 설정과 불일치가 발생합니다.

Docker가 불일치를 감지하고 "다시 만들어야 한다"고 알리는 겁니다.

### 해결

`docker-compose.yml`의 networks 섹션에 해당 옵션을 명시적으로 선언합니다.

```yaml
networks:
  eventful-network:
    driver: bridge
    driver_opts:
      com.docker.network.enable_ipv4: "true"
```

이렇게 하면 Docker가 네트워크를 재생성하더라도 동일한 설정으로 만들기 때문에 불일치가 사라집니다.

---

## 5. Spring Batch application.yml 중복 키 에러

### 에러 메시지

```
Caused by: org.springframework.beans.factory.BeanDefinitionStoreException: 
Duplicate key spring
```

### 상황

settlement-service를 처음 기동했을 때 Spring Batch 관련 설정에서 에러가 발생했습니다. YAML 문법도 맞는 것 같고, 설정 값도 올바른데 계속 Duplicate key라고 했습니다.

### 원인 분석

YAML에서는 같은 레벨에서 동일한 키가 두 번 나오면 중복으로 처리됩니다. Spring Batch 설정을 추가할 때 실수로 `spring:` 블록을 새로 시작해버렸습니다.

```yaml
# 잘못된 예시
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/settlement_service_db
  kafka:
    bootstrap-servers: localhost:9092

spring:          # ← 이미 위에 spring: 가 있는데 또 시작!
  batch:
    jdbc:
      initialize-schema: always
    job:
      enabled: false
```

YAML 파서는 두 번째 `spring:` 블록을 별도 문서가 아닌 같은 레벨의 중복 키로 인식합니다.

### 해결

하나의 `spring:` 블록 안에 모든 설정을 병합합니다.

```yaml
# 올바른 예시
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/settlement_service_db
  kafka:
    bootstrap-servers: localhost:9092
  batch:           # ← spring: 안에 함께
    jdbc:
      initialize-schema: always
    job:
      enabled: false
```

Spring Boot의 `---` 구분자는 **다른 profile 설정을 분리**할 때 쓰는 것이지, 같은 profile 내에서 설정을 나눠 쓰는 용도가 아닙니다.

---

## 6. Kafka DLQ(Dead Letter Queue)가 동작하지 않음

### 상황

`KafkaConsumerConfig`에서 `DefaultErrorHandler`와 `DeadLetterPublishingRecoverer`를 설정했는데, 처리 실패한 메시지가 DLQ 토픽으로 전혀 가지 않았습니다. 로그도 안 찍히고 그냥 조용히 사라졌습니다.

```kotlin
// KafkaConsumerConfig.kt
@Bean
fun kafkaListenerContainerFactory(...): ConcurrentKafkaListenerContainerFactory<String, String> {
    val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
    factory.consumerFactory = consumerFactory(props)

    val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate)
    val errorHandler = DefaultErrorHandler(recoverer, FixedBackOff(1000L, 3L))
    factory.setCommonErrorHandler(errorHandler)

    return factory
}
```

설정은 분명히 되어 있는데 왜 DLQ로 안 갈까요?

### 원인 분석

Consumer 메서드 안에서 try-catch로 예외를 잡아 삼키고 있었습니다.

```kotlin
@KafkaListener(topics = ["payment-events"])
fun consume(message: String) {
    try {
        val payload = objectMapper.readValue(message, PaymentCompletedPayload::class.java)
        settlementService.createSettlement(payload)
    } catch (e: Exception) {
        log.error { "처리 실패: ${e.message}" }
        // 예외를 잡고 끝 → Kafka 리스너 레벨까지 전파 안 됨
    }
}
```

`DefaultErrorHandler`는 Kafka 리스너 컨테이너 레벨에서 예외를 감지합니다. Consumer 메서드 내부에서 예외가 catch되어 버리면, 리스너 컨테이너 입장에서는 "정상 처리된 것"으로 보입니다. DLQ로 보내는 로직이 아예 실행되지 않는 겁니다.

### 해결

Consumer에서 try-catch를 제거하고 예외를 그대로 전파시킵니다.

```kotlin
@KafkaListener(topics = ["payment-events"])
fun consume(message: String) {
    val payload = objectMapper.readValue(message, PaymentCompletedPayload::class.java)
    settlementService.createSettlement(payload)
    // 예외 발생 시 전파 → DefaultErrorHandler가 감지 → 3회 재시도 → DLQ
}
```

예외가 전파되어야 `DefaultErrorHandler`가 `FixedBackOff(1000L, 3L)` 설정대로 1초 간격으로 3회 재시도하고, 모두 실패하면 `DeadLetterPublishingRecoverer`가 `{원본토픽}.DLT` 토픽으로 메시지를 보냅니다.

---

## 7. `ExponentialBackOffWithMaxRetries` Unresolved reference

### 에러 메시지

```
Unresolved reference: ExponentialBackOffWithMaxRetries
```

### 상황

Kafka DLQ 설정 시 재시도 간격을 점진적으로 늘리기 위해 `ExponentialBackOffWithMaxRetries`를 쓰려 했는데 클래스를 못 찾겠다는 에러가 났습니다.

### 원인 분석

`ExponentialBackOffWithMaxRetries`는 `spring-retry` 라이브러리의 클래스입니다. 그런데 Spring Kafka의 `DefaultErrorHandler`가 받는 `BackOff`는 **`spring-framework`의 `BackOff` 인터페이스**입니다. 두 라이브러리의 `BackOff`는 패키지도, 인터페이스도 서로 다릅니다.

| | 패키지 | 사용처 |
|---|---|---|
| `ExponentialBackOffWithMaxRetries` | `org.springframework.retry` | spring-retry (RetryTemplate 등) |
| `FixedBackOff` | `org.springframework.util.backoff` | spring-framework (DefaultErrorHandler 등) |
| `ExponentialBackOff` | `org.springframework.util.backoff` | spring-framework |

### 해결

`spring-framework`에서 제공하는 `FixedBackOff` 또는 `ExponentialBackOff`를 사용합니다.

```kotlin
// FixedBackOff: 고정 간격 재시도
val errorHandler = DefaultErrorHandler(
    DeadLetterPublishingRecoverer(kafkaTemplate),
    FixedBackOff(1000L, 3L)  // 1초 간격, 최대 3회
)

// ExponentialBackOff: 지수 증가 간격 재시도
val backOff = ExponentialBackOff(1000L, 2.0).apply {
    maxInterval = 10000L
    maxAttempts = 3
}
val errorHandler = DefaultErrorHandler(
    DeadLetterPublishingRecoverer(kafkaTemplate),
    backOff
)
```

---

## 8. 서비스마다 포트가 달라 API 호출이 불편함 — API Gateway 도입

### 상황

서비스가 늘어날수록 요청을 보낼 때마다 포트를 확인해야 했습니다.

```
POST http://서버:8081/orders        # 주문
POST http://서버:8085/auth/login    # 인증
GET  http://서버:8086/products      # 상품
GET  http://서버:8087/settlements   # 정산
```

기능을 붙일수록 포트 관리가 번거로워졌고, 보안 측면에서도 서비스 포트를 외부에 전부 노출하는 구조가 좋지 않았습니다. 또한 JWT 검증 로직이 `common-auth`에 모여 있긴 해도, 각 서비스가 직접 토큰을 검증하는 구조 자체는 여전히 분산된 상태였습니다.

### 선택지 검토

**nginx**는 설정 파일만 추가하면 돼서 빠르지만, JWT 검증 같은 인증 로직을 코드로 관리하기 어렵습니다.

**Spring Cloud Gateway**는 두 가지 버전이 있습니다.

| | Spring Cloud Gateway | Spring Cloud Gateway MVC |
|---|---|---|
| 기반 | WebFlux (비동기 논블로킹) | Spring MVC (서블릿) |
| 기존 필터 재사용 | 불가 (재작성 필요) | 가능 (`OncePerRequestFilter` 그대로) |

WebFlux는 동시 요청이 많을 때 같은 자원으로 더 높은 처리량을 낼 수 있지만, 코드 복잡도가 높아집니다. 이 프로젝트는 이미 서블릿 기반으로 작성되어 있고 `common-auth`의 필터를 그대로 재사용할 수 있는 **Spring Cloud Gateway MVC**를 선택했습니다.

### 구조 변경

**게이트웨이 도입 전**
```
클라이언트 → 각 서비스 포트로 직접 요청
             JWT 검증을 서비스마다 각자 수행
```

**게이트웨이 도입 후**
```
클라이언트 → :8080 (단일 진입점)
  → GatewayJwtFilter: JWT 검증 + Redis 블랙리스트 체크
  → X-User-Id, X-User-Role 헤더 추가 후 내부 서비스로 전달
     각 서비스: 헤더를 읽어 SecurityContext 세팅 (JWT 재검증 없음)
```

라우팅은 `/api/{서비스}/**` prefix를 붙이고 StripPrefix 필터로 내부 서비스에 전달할 때 prefix를 제거합니다.

```
/api/orders/**      → order-service:8081/orders/**
/api/auth/**        → user-service:8085/auth/**
/api/products/**    → product-service:8086/products/**
```

공개 경로(`/api/auth/login`, `/api/payments/webhook` 등)는 게이트웨이에서 JWT 검증을 건너뜁니다.

### 결과

- 클라이언트는 포트 하나(8080)만 알면 됩니다
- JWT 검증과 블랙리스트 체크가 게이트웨이 한 곳에서 처리됩니다
- 각 서비스 포트는 외부에 노출되지 않고 Docker 내부 네트워크로만 통신합니다
- 나중에 인증 방식이 바뀌어도 게이트웨이만 수정하면 됩니다

---

## 마치며

이번 프로젝트를 진행하면서 느낀 점은, 에러의 상당수가 **"다 된 줄 알았는데 사실 운 좋게 돌아가고 있었던"** 케이스라는 겁니다.

`kotlin-reflect`는 다른 서비스들이 우연히 의존성을 끌어와서 정상 동작했고, Kafka DLQ는 설정을 해놨는데 실제로는 동작하지 않는 상태였습니다. 표면적으로는 "잘 되고 있는" 것처럼 보였을 뿐이었죠.

멀티모듈 + 멀티서비스 환경에서는 각 모듈의 의존성을 명시적으로 관리하고, 실패 경로(DLQ, 재시도, 서킷브레이커)가 실제로 동작하는지 직접 검증하는 게 중요하다는 걸 배웠습니다.
