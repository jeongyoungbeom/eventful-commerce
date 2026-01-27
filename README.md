# Eventful Commerce

Kotlin + Spring Boot 기반 이벤트 드리븐 커머스 사이드 프로젝트의 기본 뼈대입니다. 각 서비스는 독립 실행이 가능하며 공통 이벤트 모델을 공유합니다.

## 모듈 구조
- `common`: 이벤트 공통 모델 `EventEnvelope`
- `order-service` (8081)
- `payment-service` (8082)
- `inventory-service` (8083)
- `shipping-service` (8084)
- `notification-service` (8085)

## 로컬 인프라 (Kafka/DB)
`infra/docker-compose.yml` 에서 Redpanda(Kafka 호환) + Postgres를 제공합니다.

```bash
cd infra
docker compose up -d
```

## 빌드 & 실행
JDK 21 이상이 필요합니다.

전체 빌드:
```bash
./gradlew build
```

개별 서비스 실행 (예: 주문 서비스):
```bash
./gradlew :order-service:bootRun
```

다른 서비스도 동일하게 모듈명만 바꿔 실행하면 됩니다.

## 헬스체크
각 서비스 기동 후:
```
GET http://localhost:<포트>/actuator/health
```

## 기타
- 공통 이벤트 모델: `common/src/main/kotlin/com/eventfulcommerce/common/EventEnvelope.kt`
- 필요한 최소 의존성만 포함했으므로, 도메인 개발에 맞춰 확장하면 됩니다.
